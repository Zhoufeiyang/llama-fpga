# P6-A greedy speculative acceptance — 2026-09-12

## Verdict

`P6-A greedy-acceptance RTL sub-gate: GO`

`P6-A sequence-equivalence reference sub-gate: GO`

`P6: IN PROGRESS`

## Implemented protocol

`p6_greedy_acceptance.sv` captures K candidate IDs and then accepts an ordered
stream of K+1 target predictions `g[0..K]`. At the first mismatch j it accepts
the prefix `[0,j)`, emits correction token `g[j]`, and exposes commit delta j.
If every candidate matches, it accepts K and emits `g[K]` as the bonus token.

The result remains stable under backpressure and blocks a new start until PS
consumes it. Abort and timeout return a rollback-required result with no commit.
Out-of-order target indices and illegal K enter a fault state.

## Verification

```text
P6_K1 mismatch_0_to_0_and_all_match=passed
P6_K2 mismatch_0_to_1_and_all_match=passed
P6_K3 mismatch_0_to_2_and_all_match=passed
P6_K4 mismatch_0_to_3_and_all_match=passed
P6_GREEDY_ACCEPTANCE_GO K1_TO_K4=1 ALL_MISMATCHES=1 BONUS=1 BACKPRESSURE=1 TIMEOUT=1
```

The accompanying Python oracle exercises every mismatch position and all-match
for K=1..4. A 100-token deterministic test with an intentionally imperfect
draft produces exactly the same token sequence as target-only greedy decoding
for every K. The complete reference suite passes 18 tests.

## Artifact

The xsim log is 790 bytes with SHA256
`d3cdc830b98ba80e7566485d74aabc1641b58844b062962d905336c1bc20f621`.

```powershell
& .\kv260\speculative\rtl\run_p6_acceptance_xsim.ps1
python -m unittest discover -s kv260/speculative/reference -p 'test_*.py'
```

This gate proves the standalone acceptance protocol and reference semantics.
Production AXI-Lite result registers and LM-head result capture remain P6-B.
