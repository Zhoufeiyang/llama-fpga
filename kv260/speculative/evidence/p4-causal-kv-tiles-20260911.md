# P4-A causal KV tile scheduler — 2026-09-11

## Verdict

`P4 causal KV tile scheduler sub-gate: GO`

`P4: IN PROGRESS`

This is a focused controller result. No full build or board artifact changed.

## Causal contract

For query candidate `q`, the scheduler emits:

1. committed KV range `[0, committed_tokens)` as 64-token DDR tiles;
2. tentative KV range `[0, q]` from the on-chip candidate buffer;
3. no request for tentative candidate `q+1` or later.

Every request locks source, query, and start-token identity until its response.
Mismatched responses fault rather than advancing. Consecutive requests alternate
ping/pong BRAM/URAM tile-buffer selection. A descriptor is rejected when
`committed_tokens + K > MAX_CONTEXT`.
An unexpected response while no tile is in flight also enters the sticky fault
state; it can no longer be silently discarded.

## Simulation

```text
P4_K1 committed=130 ddr_tiles=3 tentative=1
P4_K2 committed=130 ddr_tiles=6 tentative=2
P4_K3 committed=130 ddr_tiles=9 tentative=3
P4_K4 committed=130 ddr_tiles=12 tentative=4
P4_K4 committed=0 ddr_tiles=0 tentative=4
P4_CAUSAL_KV_TILE_SCHEDULER_GO K1_TO_K4=1
```

The committed=130 case proves the tail geometry 64+64+2 independently for each
query. The committed=0 case proves tentative-only startup.

## OOC synthesis

Vivado 2022.2 synthesis completed with zero errors and zero critical warnings.
At 3.333 ns, WNS is `+0.205 ns`; utilization is 172 LUTs and 131 registers,
with no BRAM or DSP in the control-only module. DCP SHA256:

`ebaf76f07eca8c99c348205ca57d8b93e68de1ef69526bd698475f37a7abbdcf`

This does not yet prove attention arithmetic. The remaining P4 work is the
QK/softmax/V datapath and numerical equivalence under the emitted causal masks.
