# P3-B production batch descriptor contract — 2026-09-12

## Gate status

`P3-B production control-plane sub-gate: GO`

`P2/P3 production numerical datapath and projection sequencing: IN PROGRESS`

No Vivado synthesis or implementation was run.

## Implemented source path

The A53 AXI-Lite map now accepts a held-valid descriptor containing GEMV/GEMM
mode, K=1..4, projection tag, layer ID, rows, and beats per row. The descriptor
crosses the top-level control boundary into the production `GenMemCmdLenAlign`
instance. Invalid shapes raise a sticky fault; done/error are returned through
`StateGen` and the AXI-Lite status register.

The command generator freezes the legacy token and S2MM token counters during
an active descriptor. Its completion counter observes only accepted production
data beats whose real bus tag matches the requested projection. The physical
weight-beat bound is `rows * beatsPerRow` and is intentionally independent of
K, preserving the weight-amortization invariant.

## Verification

- Full Scala compilation: 155 Scala sources and one Java source, success.
- Production `top.EdgeLLMInst` elaboration: success after explicit synchronous
  control/data clock-domain tags; generated RTL completed Spinal checks.
- Focused AXI-Lite xsim:

```text
P3B_PRODUCTION_DESCRIPTOR_GO HELD_UNTIL_READY=1 MODE=GEMM K=4 PROJECTION=42 LAYER=17 ROWS=4096 BEATS=32 DONE=1 INVALID_FAULT=1
```

- Refreshed P5-B and P6-B AXI-Lite regressions both pass.

This gate proves the production control contract and K-independent weight-beat
accounting boundary. It does not prove that the production MAC array consumes
K activation rows from one streamed weight beat; that remains the next P2
datapath integration gate.
