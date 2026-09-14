# P5-H physical DataMover status drain — 2026-09-14

`P5 physical MM2S/S2MM status source/xsim gate: GO`

`P5 live-board gate: IN PROGRESS`

## Delivered integration

The production `SplitAxiDatamover` no longer discards MM2S/S2MM status streams.
For the KV260 four-HP-port configuration, `SplitDmaStatusJoin` waits until all
four physical DataMover lanes present the status for the same logical command,
then retires them atomically under downstream backpressure. Error payloads are
OR-reduced and the vendor `mm2s_err/s2mm_err` outputs are propagated.

`DataPath` now counts speculative commands only after the actual post-remap
DataMover command handshake. Independent MM2S and S2MM epoch trackers retire
only on the corresponding aggregate physical status handshake. The AXI-Lite
drain bit is the conjunction of the GenMem KV ingress/data barrier and both
physical status trackers; error is the OR of all tracker and vendor errors.

Thus result availability alone cannot publish a speculative pointer. Rollback
does not erase old physical traffic, and the next START/COMMIT waits for both
read and write status paths to reach zero.

## Acceptance evidence

```text
P5G_OUTSTANDING_TRACKER_GO ROLLBACK_DRAINS=1 EPOCH_OVERLAP_FAULT=1 NEW_EPOCH_AFTER_ZERO=1
P5H_SPLIT_STATUS_JOIN_GO LANES=4 ALL_REQUIRED=1 BACKPRESSURE=1 ERROR_OR=1 ATOMIC_RETIRE=1
P5B_AXILITE_POINTER_CONTROL_GO REGISTERS=1 EARLY_COMMIT_BLOCKED=1 COMMIT_AFTER_RESULTS=1 DMA_DRAIN_BEFORE_COMMIT=1 ROLLBACK=1 LAST_CONTEXT_1024=1 TXN_EPOCH=1 FAULT=1 PERF_WINDOW=1 CLEAR_PULSES=4
```

Complete `top.EdgeLLMInst` elaboration passes after the physical-status wiring
with zero errors. No synthesis or implementation run was performed.

## Final validation scope

The one-time final hardware run must still confirm vendor status ordering and
absence of underflow on the connected split=4 DataMovers. Those observations
are board acceptance evidence, not a reason to repeat synthesis during source
development.
