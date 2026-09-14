# P5-G speculative DMA drain barrier — 2026-09-14

`P5 production transaction-completion source/xsim gate: GO`

`P5 live-board gate: IN PROGRESS`

## Delivered artifact

`util.SpeculativeOutstandingTracker` counts speculative KV S2MM commands when
they enter the production command FIFO and retires them only on the accepted
end-of-frame data beat. The count survives rollback: physical DDR traffic from
the old epoch must drain to zero before a new epoch may start. Changing epoch
with outstanding traffic raises a sticky hardware error.

`GenMemCmdLenAlign` connects the tracker to its real KV command/data boundaries.
The drained/error state crosses the existing DataPath-to-AXI-Lite status path.
AXI-Lite START and COMMIT now require the drain barrier, and the status register
at `0x130` exposes `drained` on bit 4 and the tracker error on bit 5. Rollback
remains immediate but a following START is blocked until old traffic drains.

## Acceptance evidence

Focused generated-RTL xsim:

```text
P5G_OUTSTANDING_TRACKER_GO ROLLBACK_DRAINS=1 EPOCH_OVERLAP_FAULT=1 NEW_EPOCH_AFTER_ZERO=1
```

Updated production AXI-Lite regression:

```text
P5B_COMMIT base=100 accepted=2 committed=102
P5B_ROLLBACK committed=102
P5B_LAST_CONTEXT committed=1024
P5B_AXILITE_POINTER_CONTROL_GO REGISTERS=1 EARLY_COMMIT_BLOCKED=1 COMMIT_AFTER_RESULTS=1 DMA_DRAIN_BEFORE_COMMIT=1 ROLLBACK=1 LAST_CONTEXT_1024=1 TXN_EPOCH=1 FAULT=1 PERF_WINDOW=1 CLEAR_PULSES=4
```

Complete `top.EdgeLLMInst` elaboration passes after integration with zero
errors. No synthesis or implementation run was performed.
