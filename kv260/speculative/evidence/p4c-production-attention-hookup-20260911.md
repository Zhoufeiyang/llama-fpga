# P4-C production attention hookup — 2026-09-11

## Verdict

`P4-C source-integration sub-gate: GO`

`P4-C generated-RTL sub-gate: NOT YET CLAIMED`

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

An attempted isolated `EdgeLLMInst` elaboration did not reach Spinal elaboration
because the temporary WSL launcher stalled during startup and was stopped. No
generated-RTL equivalence claim is made from that attempt. The next narrow
gate is successful top-level elaboration followed by an interface/netlist check
for the `0x28` register-to-softmax path.
