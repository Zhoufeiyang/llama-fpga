# P7-G production performance counters — 2026-09-12

## Scope

P7-G adds passive, transaction-scoped hardware counters to the production
speculative-verification path.  The counters observe existing handshakes and
never drive `valid`, `ready`, addresses, data, or scheduler state.

Measured quantities are:

- target projection weight bytes accepted by the production tagged stream;
- KV read bytes accepted by the production KV-cache stream;
- KV write bytes accepted by the production S2MM stream;
- cycles while a speculative batch descriptor is active;
- cycles where the target-weight stream is valid but backpressured.

The low 32 bits are exposed read-only at AXI-Lite offsets `0x180` through
`0x190`.  Counters clear on descriptor acceptance and stop when the descriptor
is no longer active.  The underlying implementation is 64 bit to avoid short
measurement overflow.

## Verification

`util.SpeculativePerfCountersTest` elaborates the counter block.  The focused
Vivado xsim regression checks byte scaling, active-window gating, simultaneous
events, stalls, and clearing:

```text
P7G_PERF_COUNTERS_GO WEIGHT_BYTES=640 KV_READ_BYTES=640 KV_WRITE_BYTES=128 VERIFY_CYCLES=12 MEMORY_STALLS=2 RESULT_STALLS=2 PASSIVE=1
```

The production AXI-Lite regression injects distinct 64-bit values and proves
the five low-word register mappings while retaining all P3-B descriptor checks:

```text
P3B_PRODUCTION_DESCRIPTOR_GO HELD_UNTIL_READY=1 MODE=GEMM K=4 PROJECTION=42 LAYER=17 ROWS=4096 BEATS=32 DONE=1 INVALID_FAULT=1 PERF_REGS=5
```

The P5-B and P6-B AXI-Lite regressions also pass after the interface extension,
and complete-top SpinalHDL elaboration passed before this evidence record was
created.

## Gate decision

`P7-G production counter source/register integration: GO`

`P7-G board measurement: IN PROGRESS`

No bandwidth, throughput, speedup, acceptance, energy, or power result is
claimed by this source-level gate.  Result-output stall counting exists in the
standalone counter primitive but is not yet connected to a trustworthy
production result backpressure event, so it is deliberately not exposed as a
production measurement.

