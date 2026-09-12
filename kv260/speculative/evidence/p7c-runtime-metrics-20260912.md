# P7-C runtime metrics instrumentation — 2026-09-12

## Verdict

`P7-C passive runtime-metrics sub-gate: GO`

`P7-C board-measurement sub-gate: IN PROGRESS`

`P7: IN PROGRESS`

## Instrumentation

`spec_runtime_metrics_t` accumulates the following without changing scheduling
decisions or PL control values:

- started, completed, and failed transactions;
- drafted, logical target-result, accepted-draft, and emitted token counts;
- all-match and mismatch transaction counts;
- rollback count, result polling iterations, and output-backpressure stalls.

The counters persist across transactions, can be read through
`spec_runtime_get_metrics()`, and can be cleared explicitly at an experiment
boundary with `spec_runtime_reset_metrics()`.

## Verification

Every K=1..4 mismatch/all-match unit-test case checks exact counter values in
addition to output tokens and MMIO writes. Timeout and PL-fault cases each
verify one failed transaction and one rollback. Strict host C99 compilation,
freestanding ARM compilation, and the complete A53 adapter link all pass.

```text
P7A_PS_RUNTIME_GO K1_TO_K4=1 BACKPRESSURE=1 TIMEOUT_ROLLBACK=1 PL_FAULT_ROLLBACK=1 METRICS=1
P7B_BAREMETAL_BUILD_GO ARCH=AARCH64 MMIO_ADAPTER=1 INITIAL_TARGET=1 SEQUENTIAL_SAFE_LAUNCH=1
```

This closes only metrics instrumentation. Publication numbers require a live
board run with fixed prompts and K=1..4. Timing, DDR traffic, acceptance rate,
tokens/s, energy/token, and target-only sequence equality must be reported from
that run rather than inferred from host counters.
