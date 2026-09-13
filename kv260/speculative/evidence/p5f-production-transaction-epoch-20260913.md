# P5F production transaction epoch and commit gate (2026-09-13)

## Verdict

`P5 transaction-active/address-freeze sub-gate: GO`

`P5 live-board sub-gate: IN PROGRESS`

No synthesis, implementation, bitstream generation, or board programming was
run for this sub-gate.

## Production change

AXI-Lite now separates the persistent speculative feature enable from the
active transaction window. Every accepted START freezes `base`, `K`, and an
eight-bit epoch. Those values propagate through `DataPath_xN` into the real
`DataPath` and `GenMemCmdLenAlign`; tentative KV read/write selection uses the
frozen base only while the transaction is active. Commit is rejected until the
complete `g[0..K]` result block is present. Rollback closes the active epoch,
and late argmax events are ignored because result capture is active-gated.

## Acceptance evidence

```text
P3B_PRODUCTION_DESCRIPTOR_GO HELD_UNTIL_READY=1 MODE=GEMM K=4 PROJECTION=42 LAYER=17 ROWS=4096 BEATS=32 DONE=1 INVALID_FAULT=1 PERF_REGS=5
P5B_COMMIT base=100 accepted=2 committed=102
P5B_ROLLBACK committed=102
P5B_LAST_CONTEXT committed=1024
P5B_AXILITE_POINTER_CONTROL_GO REGISTERS=1 EARLY_COMMIT_BLOCKED=1 COMMIT_AFTER_RESULTS=1 ROLLBACK=1 LAST_CONTEXT_1024=1 TXN_EPOCH=1 FAULT=1 PERF_WINDOW=1 CLEAR_PULSES=4
P6B_AXILITE_RESULTS_GO CANDIDATES=4 TARGETS=5 INITIAL_TARGET=1 HOLD_UNTIL_ACK=1 OVERWRITE_BLOCKED=1
```

The complete `EdgeLLMInst` source elaborates with zero errors. This gate proves
the production control/address transaction boundary. It does not replace the
remaining P3 projection command grant or P4 physical KV requester verification.
