# P5-B production pointer-control hookup — 2026-09-12

## Verdict

`P5-B production AXI-Lite control sub-gate: GO`

`P5: IN PROGRESS`

No full synthesis, implementation, bitstream generation, or board programming
was run. This sub-gate connects pointer state to the production AXI-Lite control
block. Production KV write-command address integration remains open.

## Production register behavior

The production `AxiLiteCtrl` now implements:

| Address | Name | Behavior |
| --- | --- | --- |
| `0x100` | `SPEC_START` | freezes the committed pointer as the speculative base |
| `0x104` | `SPEC_CONFIG` | programs K in bits `[2:0]` |
| `0x108` | `COMMITTED_LEN` | reads/programs the attention-visible pointer |
| `0x10c` | `SPEC_BASE_POS` | reads the frozen transaction base |
| `0x120` | `COMMIT_DELTA` | accepted count `[2:0]`, commit strobe at bit 8 |
| `0x124` | `ROLLBACK` | restores speculative pointer without reducing committed length |
| `0x130` | `SPEC_STATUS` | idle, active, fault, and speculative pointer |

The already-connected `io.speculativeCommitted` output is driven by this
committed register. It therefore continues through `DataPath_xN` and `DataPath`
to the P4 production attention length selector. Candidate reservation changes
the speculative pointer only; attention visibility changes only on commit.

## Elaboration and xsim

SpinalHDL 1.10.2a compiled the modified production source and generated
`AxiLiteCtrl.v` with no elaboration errors. A self-checking AXI-Lite bus test
performed real register transactions against that generated RTL:

```text
P5B_COMMIT base=100 accepted=2 committed=102
P5B_ROLLBACK committed=102
P5B_AXILITE_POINTER_CONTROL_GO REGISTERS=1 COMMIT=1 ROLLBACK=1 FAULT=1
```

The test also verifies that K=0 is rejected and sets the sticky fault bit.

## Artifacts

- Generated RTL: 26,325 bytes, SHA256
  `da47269f52a19daddbe3cd993f62f33d4cec70ba681b6739b611a53898bcbf92`.
- xsim log: 668 bytes, SHA256
  `13e133505ffd564308781700114c31e2f8d95f4f2a50e27fa6c91ebcfbe62689`.

Reproduce the generated-RTL simulation after elaborating
`top.AxiLiteCtrlP5Test`:

```powershell
& .\kv260\speculative\rtl\run_p5b_axilite_xsim.ps1
```
