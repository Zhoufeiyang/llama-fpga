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
- `0x138`: initial target prediction `g[0]`, read/write;
- `0x140..0x150`: target IDs 0..4, read-only;
- `0x154`: result acknowledgement, write pulse.

## Verification

The self-checking test uses the generated production `AxiLiteCtrl.v`, not a
standalone register model. It writes all candidates and `g[0]`, starts the
transaction, injects four following production argmax events, reads all five
results, verifies retention, verifies that a second start is faulted before
acknowledgement, then acknowledges and confirms that the result block clears.

```text
P6B_AXILITE_RESULTS_GO CANDIDATES=4 TARGETS=5 INITIAL_TARGET=1 HOLD_UNTIL_ACK=1 OVERWRITE_BLOCKED=1
```

The generated RTL is 30,515 bytes with SHA256
`2e4486b087d2eaa046f04486798f11d68d0b19a78cc5890dfffd047782441ba1`.
The xsim log is 628 bytes with SHA256
`7b178dfd2b6d08994f43f2ddd010a528f00d168fe574071d3bd1205ef4244556`.

```powershell
& .\kv260\speculative\rtl\run_p6b_axilite_xsim.ps1
```

Together, P6-A and P6-B prove the greedy acceptance semantics, target-only
sequence equivalence, and the production PS/PL result-retention contract.
Board-level LM-head event-count observation remains a final validation item.
