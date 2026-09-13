# Speculative token ingress (2026-09-13)

Implemented a standalone `SpeculativeTokenIngress` in `scala/src/main/scala/top/SpeculativeTokenIngress.scala`.
The component snapshots the AXI-Lite candidate IDs and K on a validated launch,
emits exactly `candidateIds[0] .. candidateIds[K-1]` on a ready/valid Stream,
holds the current token stable under backpressure, and emits the decode user tag
in the low nibble while carrying candidate position in the two high bits.
`GenMemCmdLenAlign` strips those high bits before the legacy parameter-tag
network and holds q independently for the full token transaction. Invalid K
and overlapping starts are rejected with a sticky fault.

`AxiLiteCtrl` exposes the new stream while retaining the legacy `tokenIndex`
Flow unchanged. `DataPath_xN` broadcasts the speculative stream to all cores and
gates the legacy branch only when a speculative token is present, preserving
legacy behavior when speculation is disabled.

The focused test is `speculative_token_ingress_tb.sv`; run it with
`run_speculative_token_ingress_xsim.ps1` after elaborating
`top.SpeculativeTokenIngressTest`. The test covers ordered K=3 output,
backpressure stability, disable cancellation/quiet output, and invalid K.
Vivado synthesis and implementation were intentionally not run.

## Acceptance evidence

```text
SPECULATIVE_TOKEN_INGRESS_GO ordered=3 q_metadata=1 backpressure=1 disable_quiet=1 invalid_k=1
SPECULATIVE_TOKEN_CONTEXT_GO Q_HELD_UNTIL_ACCEPT=1 ROUTE_TAG_COMPATIBLE=1 LEGACY_NONDESTRUCTIVE=1
```

The complete `EdgeLLMInst` Scala source also elaborates with zero errors.  This
gate proves ordered and backpressurable candidate delivery plus stable q
metadata.  It does not prove that the target projection scheduler consumes the
K candidates in one physical matrix stream; that remains a separate P3 gate.
