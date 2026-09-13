# P7-H runtime hardware-metrics binding — 2026-09-12

## Scope

The freestanding PS runtime now samples the five P7-G production counters after
the complete `K+1` target result block is visible and before acknowledging the
result buffer.  Per-transaction 32-bit hardware samples are accumulated into
64-bit software totals alongside the existing speculative runtime statistics.

Register mapping:

- `0x180`: target-weight bytes;
- `0x184`: KV read bytes;
- `0x188`: KV write bytes;
- `0x18c`: target verification-window cycles;
- `0x190`: target-memory stall cycles.

The PL measurement window is cleared by a valid speculative start and closes
when result count reaches `K+1`, so PS acceptance, token emission, and result
acknowledgement latency do not inflate the hardware verification-cycle count.

## Verification

The host runtime test injects distinct values in all five registers and checks
their exact accumulation for every K=1..4 mismatch/all-match case.  The full
runtime suite also retains 100-token target-only sequence equivalence and the
timeout fallback case:

```text
P7A_PS_RUNTIME_GO K1_TO_K4=1 BACKPRESSURE=1 TIMEOUT_ROLLBACK=1 PL_FAULT_ROLLBACK=1 TARGET_FALLBACK=1 METRICS=1 HW_METRICS=5
P7E_RUNTIME_100_GO K1_TO_K4=1 TARGET_ONLY_EQUIVALENCE=1 TIMEOUT_FALLBACK=1
P7B_BAREMETAL_BUILD_GO ARCH=AARCH64 MMIO_ADAPTER=1 INITIAL_TARGET=1 SEQUENTIAL_SAFE_LAUNCH=1 NGRAM_BOUND=1 TARGET_FALLBACK=1
```

## Gate decision

`P7-H PS hardware-metrics binding: GO`

Live counter values remain unclaimed until the final production RTL is built
and exercised on KV260.
