# P6-B production target-result buffer — 2026-09-12

## Verdict

`P6-B production result-buffer sub-gate: GO`

`P6: GO`

## Production integration

`top.AxiLiteCtrl` now stores four PS-provided candidate IDs and captures up to
five ordered target argmax IDs from the production LM-head completion path.
The result count and IDs remain readable until PS writes the explicit result
acknowledgement register. A speculative launch is rejected while an older
result block is pending, preventing silent overwrite.

Register additions are:

- `0x110..0x11c`: candidate IDs 0..3, read/write;
- `0x134`: captured target-result count, read-only;
- `0x140..0x150`: target IDs 0..4, read-only;
- `0x154`: result acknowledgement, write pulse.

## Verification

The self-checking test uses the generated production `AxiLiteCtrl.v`, not a
standalone register model. It writes all candidates, injects five production
argmax events, reads every result, verifies retention, verifies that a second
start is faulted before acknowledgement, then acknowledges and confirms that
the result block clears.

```text
P6B_AXILITE_RESULTS_GO CANDIDATES=4 TARGETS=5 HOLD_UNTIL_ACK=1 OVERWRITE_BLOCKED=1
```

The generated RTL is 30,125 bytes with SHA256
`179d4183f2fdf53abeee487d658472b7360c54138d2eed1eeb5b57f48be6ffda`.
The xsim log is 611 bytes with SHA256
`24d55af5579daf15d2cc7181d2f967e3dfe4de5f2edfe615026beeab0557f80f`.

```powershell
& .\kv260\speculative\rtl\run_p6b_axilite_xsim.ps1
```

Together, P6-A and P6-B prove the greedy acceptance semantics, target-only
sequence equivalence, and the production PS/PL result-retention contract.
Board-level LM-head event-count observation remains a final validation item.
