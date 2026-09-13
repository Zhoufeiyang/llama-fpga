# P3D/E production automatic sequencing and command grant (2026-09-13)

## Verdict

`P3 automatic projection-sequence control sub-gate: GO`

`P3 downstream batched-result consumption sub-gate: IN PROGRESS`

No synthesis, implementation, bitstream generation, or board programming was
run for this source-level gate.

## Production change

`AxiLiteCtrl` now launches the on-chip `SpeculativeProjectionSequencer` with
the same validated START that snapshots K and the candidate block. Its 225
production descriptors cover Q/K/V/O/G/U/D for all 32 layers followed by LM
head. Completion identity comes from the real command generator. The V-to-O
barrier is driven by the terminal count of the production V-weighted AXPY
vector; the U-to-D barrier is driven by the real G/U activation `last` event.
Rollback or accepted commit aborts the active descriptor epoch so a late
completion cannot retire work in a later transaction.

`GenMemCmdLenAlign` now gates the actual MM2S command stream. The eight W4
weight tags may issue only while a matching descriptor is active. Token,
normalization, and KV auxiliary commands continue between descriptors. This
prevents the old grouped QKV/MLP command mux from running ahead of completion
and losing the first beats of the next matrix.

## Acceptance evidence

```text
P3D_PROJECTION_COMMAND_GATE_GO WEIGHT_TAGS=8 MATCH_REQUIRED=1 AUXILIARY_PROGRESS=1 LEGACY_BYPASS=1
P3E_AUTO_SEQUENCE_K1 projections=225 weight_beats=51617792
P3E_AUTO_SEQUENCE_K2 projections=225 weight_beats=51617792
P3E_AUTO_SEQUENCE_K3 projections=225 weight_beats=51617792
P3E_AUTO_SEQUENCE_K4 projections=225 weight_beats=51617792
P3E_AXILITE_AUTO_SEQUENCE_GO K1_TO_K4=1 PROJECTIONS=225 BARRIERS_REAL_INPUTS=1 WEIGHT_PASS_K_INDEPENDENT=1
P3_PRODUCTION_SEQUENCE_GO K1_TO_K4=1 ORDER=Q,K,V,ATTN,O,G,U,MLP,D,LM_HEAD WEIGHT_PASS_K_INDEPENDENT=1 LEGACY_K_GT1_REJECT=1
```

The complete `EdgeLLMInst` source elaborates with zero errors after the command
gate and real barrier hookups. P3 remains open for production consumption of
the row-major K-lane results; the control path alone is not a numerical board
claim.
