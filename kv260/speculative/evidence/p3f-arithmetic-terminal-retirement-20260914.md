# P3F arithmetic-terminal projection retirement (2026-09-14)

## Verdict

`P3 arithmetic-terminal retirement sub-gate: GO`

`P3 downstream K-lane ordering sub-gate: IN PROGRESS`

No synthesis, implementation, bitstream generation, or board programming was
run for this source-level gate.

## Production change

The production descriptor is no longer retired by the last incoming weight
beat. `SpeculativeProjectionRetirement` maps each parameter tag to its real
arithmetic output tag and counts K-scaled output terminals. Q/K/V/O/G/U and LM
head retire on the last scalar result; D retires on the last packed vector
result. `GenMemCmdLenAlign` keeps the descriptor and MM2S segment grant active
after weight input completes, then releases it only on a matching tagged/layer
retirement event. A rolled-back epoch releases its incomplete descriptor.

This closes the race in which the next matrix could be granted while the
shared multiplier/reduction/FP32 accumulation pipeline was still draining.

## Acceptance evidence

```text
P3F_SCALAR_RETIRE_K1 outputs=3
P3F_SCALAR_RETIRE_K2 outputs=6
P3F_SCALAR_RETIRE_K3 outputs=9
P3F_SCALAR_RETIRE_K4 outputs=12
P3F_PROJECTION_RETIREMENT_GO SCALAR_K1_TO_K4=1 VECTOR_D=1 WRONG_TAG_IGNORED=1 ARITHMETIC_TERMINAL=1
```

The complete `EdgeLLMInst` source elaborates with zero errors after the
retirement event is connected from the actual `engine.scalarOut` and
`engine.vecOut` streams back into `GenMemCmdLenAlign`.
