# P4-C production attention hookup — 2026-09-11

## Verdict

`P4-C source-integration sub-gate: GO`

`P4-C generated-RTL sub-gate: GO`

`P4: IN PROGRESS`

This change modifies the production SpinalHDL hierarchy rather than only the
standalone speculative controller. No full Vivado synthesis, implementation,
bitstream, or board programming was run.

## Actual datapath connection

AXI-Lite register `0x28` now carries `speculativeEnable`, candidate query
`q=0..3`, and the committed-token count through `DataPath_xN` and `DataPath`
into `AttnSubMod`. When enabled, the instantiated production
`SerialSafeSoftmax` receives the inclusive causal last index
`committed + q`. When disabled, it continues to receive the legacy
`status.token` value.

The existing production flow is preserved on both sides:

```text
QKMul.output -> SerialSafeSoftmax.input(1)
SerialSafeSoftmax.output -> ScalarOutSubMod.softmax2Axpy -> V AXPY
```

Thus the selector changes the causal score window of the real softmax instance
without introducing a second arithmetic engine.

## Verification

The complete Scala project compiled 149 Scala sources and one Java source with
zero errors under Scala 2.11.12 and SpinalHDL 1.10.2a. Two pre-existing import
shadowing warnings remain in `GenSplitAlignTransfer.scala` and are unrelated.

`EdgeLLMInst` then completed checks, transforms, and Verilog generation in a
stage-specific directory. The 2,044,431-byte generated `DataPath_xN.v` has
SHA256:

`92d81cbc6b264907388486b83c80acdf0c843a5509d7929c0153674cd1d1435f`

Structural inspection confirms that address `0x28` writes bits `[0]`, `[3:2]`,
and `[25:16]`, that these signals reach `AttnSubMod`, that the generated
softmax length selects `speculativeCommitted + speculativeQuery`, and that the
same generated design connects QKMul to SerialSafeSoftmax and softmax output to
the existing AXPY input mux.
