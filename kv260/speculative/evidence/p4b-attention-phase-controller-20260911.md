# P4-B causal attention phase controller — 2026-09-11

## Verdict

`P4-B numerical-reference sub-gate: GO`

`P4-B attention-phase-controller sub-gate: GO`

`P4: IN PROGRESS`

No full synthesis, implementation, bitstream generation, or board programming
was run. This gate covers the causal numerical contract and the controller
boundary to the existing QKMul, SerialSafeSoftmax, and V-AXPY kernels.

## Contract

For each committed-prefix tile, the controller fetches DDR once for query 0
and replays the selected on-chip ping/pong buffer for queries 1 through K-1.
After all committed tiles, it processes the query-dependent tentative tails,
launches K softmax rows with exactly `committed_tokens + q + 1` scores, and
repeats the tile-major walk for V accumulation. Phase, source, query, and
tile-start identities stay locked until completion. Wrong or unsolicited
completions enter a sticky fault.

## Numerical acceptance

The NumPy oracle uses stable max-subtracted softmax with the production `-16`
clip and models INT8 KV scale/zero dequantization. Sixteen reference tests pass.
For K=1..4, tiled attention is elementwise equivalent to dense causal attention;
mutating a future tentative candidate cannot change an earlier query result.

## RTL simulation acceptance

```text
P4B_TILE_REUSE_K1 qk_ops=4 v_ops=4 qk_ddr=3 v_ddr=3
P4B_TILE_REUSE_K2 qk_ops=8 v_ops=8 qk_ddr=3 v_ddr=3
P4B_TILE_REUSE_K3 qk_ops=12 v_ops=12 qk_ddr=3 v_ddr=3
P4B_TILE_REUSE_K4 qk_ops=16 v_ops=16 qk_ddr=3 v_ddr=3
P4_SPINAL_CONTROLLER_GO K1_TO_K4=1 DDR_TILE_READS_K_INDEPENDENT=1 BUFFER_FAULT=1
```

The 130-token committed prefix forces three DDR fetches per phase regardless of
K, plus one query-dependent tentative operation per query. This proves both the
QK/softmax/V ordering and the intended historical-KV reuse mechanism. At K=4,
the historical read volume is exactly 1.0x K=1 in this aligned controller test.

## OOC synthesis acceptance

Vivado 2022.2 synthesized the controller with zero errors and zero critical
warnings. At 3.333 ns it reports WNS `+1.186 ns`, 118 LUTs and 56 registers,
with no BRAM or DSP. DCP SHA256:

`33d216f8531a921425db382bbe67d2b29a87d9a13fa3380cab1bd916c73e524e`

The historical synthesis numbers and DCP hash below refer to the pre-reuse
controller and are retained only as provenance; they are not acceptance
evidence for this revision. P4 remains in progress until the revised controller
is connected to the physical KV tile requester and passes the single final
implementation/board run.
