# P5-C production KV address integration — 2026-09-12

## Verdict

`P5-C production KV data-address sub-gate: GO`

`P5: IN PROGRESS`

No full synthesis, implementation, bitstream generation, or board programming
was run. The remaining P5 item is production integration of partial scale/zero
metadata read-modify-write.

## Production changes

The production `GenMemCmdLenAlign` now instantiates the shared
`SpeculativeKvPosition` selector for both KV reads and KV writes. In speculative
mode, candidate q uses physical token slot `committed + q`; legacy mode retains
its original token counter.

The selected write position is captured in a 64-entry FIFO on token launch.
Every K-cache, V-cache, K-metadata, and V-metadata command for that token uses
the captured value, so a later AXI-Lite update of q cannot alter an in-flight
address. While speculative mode is enabled, the legacy write counter mirrors
the committed pointer instead of advancing through rejected candidates. This
makes target-only fallback resume at the accepted prefix.

The same selector now drives production KV read lengths, closing the earlier
gap where only the softmax length used `committed + q`.

## Verification

Focused generated RTL xsim result:

```text
P5C_Q0 physical_kv_slot=100
P5C_Q1 physical_kv_slot=101
P5C_Q2 physical_kv_slot=102
P5C_Q3 physical_kv_slot=103
P5C_POSITION_SELECTOR_GO LEGACY=1 K1_TO_K4=1 OVERFLOW=1
```

The complete KV260 production top then passed SpinalHDL checks, transforms, and
Verilog generation. Structural inspection confirms two selector instances,
the write-position FIFO capture, all six production KV read-length uses, both
KV data write addresses, and the legacy-counter mirror.

## Artifacts

- Selector RTL: 1,155 bytes, SHA256
  `5e3081946940989a9c86f42f89550ccc6a62dddb0afbfd70aed75358bd50c9c0`.
- Selector xsim log: 686 bytes, SHA256
  `c85cc00d57d220232524d32d1c2ef8dc8af617c69360e3e33d23974c89761126`.
- Production top RTL: 2,052,675 bytes, SHA256
  `1a610d2c8e314c9b38f069a2f5a51e62dcc8edd85089ae9cfe20c7cf71ba58c7`.

This proves address selection and production source integration, not routed
timing or physical DDR traffic on the board.
