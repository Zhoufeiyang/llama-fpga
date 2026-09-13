# P4F K independent historical KV tile reuse 20260913

## Verdict

`P4-B tile-major control sub-gate: GO`

`P4 physical KV requester integration: IN PROGRESS`

No synthesis, implementation, bitstream generation, or board programming was
run for this sub-gate.

## Source change

`P4AttentionPhaseController` now emits an explicit `fetch` bit. For a committed
prefix tile, query 0 requests a DDR fill and queries 1 through K-1 reuse the
same ping/pong buffer. The buffer and DDR offset advance only after all K
queries consume the tile. Tentative tails remain query-specific. All K softmax
rows complete before the controller rewinds the same tile-major traversal for
V accumulation.

## Acceptance evidence

Scala compilation and elaboration completed with zero errors. Vivado xsim
2022.2 produced:

```text
P4B_TILE_REUSE_K1 qk_ops=4 v_ops=4 qk_ddr=3 v_ddr=3
P4B_TILE_REUSE_K2 qk_ops=8 v_ops=8 qk_ddr=3 v_ddr=3
P4B_TILE_REUSE_K3 qk_ops=12 v_ops=12 qk_ddr=3 v_ddr=3
P4B_TILE_REUSE_K4 qk_ops=16 v_ops=16 qk_ddr=3 v_ddr=3
P4_SPINAL_CONTROLLER_GO K1_TO_K4=1 DDR_TILE_READS_K_INDEPENDENT=1 BUFFER_FAULT=1
```

The 130-token prefix contains three 64-token-aligned committed DDR tiles and
one tentative operation per query. Therefore K=4 historical fetch traffic is
exactly `1.0x` K=1 at this controller boundary, satisfying the planned
`<=1.2x` mechanism threshold. Total arithmetic tile operations remain `4*K`,
which is required to update K independent query contexts.

The production completion-adapter regression also passes after adding the new
bundle field:

```text
P4_COMPLETION_ADAPTER_GO QK_COUNTED=1 SOFTMAX_LAST=1 V_LAST=1 METADATA=PHASE,SOURCE,BUFFER,QUERY,START EARLY_V_FAULT=1 UNSOLICITED_SOFTMAX_FAULT=1
```

This result proves the corrected controller schedule. It does not yet claim
that the top-level DDR command path obeys `fetch`; that claim requires the
physical requester hookup and the final hardware/board run.
