# P5-A tentative KV pointer commit — 2026-09-12

## Verdict

`P5-A pointer-commit RTL sub-gate: GO`

`P5-A metadata-line RMW sub-gate: GO`

`P5: IN PROGRESS`

No full synthesis, implementation, bitstream generation, or board programming
was run. P5 remains open until these interfaces are connected to the production
AXI-Lite state and KV write-command path.

## Implemented contract

`p5_tentative_kv_commit.sv` freezes `spec_base_token` at transaction start and
maps candidate slot `i` to the DDR token slot `spec_base_token + i`. Candidate
data is not copied at resolution. Accept advances `committed_tokens` by the
accepted prefix; rejected bytes remain physically present but invisible and can
be overwritten by the next transaction.

The module keeps `visible_tokens == committed_tokens` throughout verification,
so the rejected tail can never become part of the next attention range. Abort
and timeout restore only the speculative pointer and never decrement the
committed pointer. Wide address arithmetic rejects any beat outside the
configured KV region or token stride.

`p5_metadata_line_merge.sv` is the 64-byte read-modify-write merge primitive for
four 32-bit scale/zero metadata entries. It changes only entries whose absolute
token indices fall in the selected line and preserves all neighbouring entries.

## Vivado xsim result

```text
P5_ACCEPT_0 committed=100
P5_ACCEPT_1 committed=101
P5_ACCEPT_2 committed=102
P5_ACCEPT_3 committed=103
P5_ACCEPT_4 committed=104
P5_ABORT committed=200
P5_TIMEOUT committed=300
P5_POINTER_COMMIT_GO ACCEPT_0_TO_4=1 ABORT_TIMEOUT=1 REGION_BOUNDS=1
P5_METADATA_BOUNDARY_15_16 preserved=1
P5_METADATA_BOUNDARY_31_32 preserved=1
P5_METADATA_RMW_GO BOUNDARIES_15_16_31_32=1
```

The tests exercise K=1..4, every legal accepted prefix for K=4, full rejection,
partial acceptance, all acceptance, context limit 4096, repeated overwrite of
the same tentative slots, abort, timeout, DDR address bounds, and metadata
crossings at 15/16 and 31/32. All committed neighbours retain their old bits.

## Reproduction and artifact

```powershell
& .\kv260\speculative\rtl\run_p5_xsim.ps1
```

The self-checking combined log is
`kv260/speculative/build/p5_xsim/p5_xsim_combined.log`, 1,480 bytes, SHA256
`3407303fe15c7afb43d21c7d18ffa6d9cdbd84bfee3aa5ac048c815c1b346a91`.
