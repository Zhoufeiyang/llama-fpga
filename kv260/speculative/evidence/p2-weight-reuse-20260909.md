# P2 W4 weight-reuse scheduler evidence — 2026-09-09

`P2 weight-reuse scheduler sub-gate: GO`

`P2 overall: IN PROGRESS`

## Implemented contract

The synthesizable scheduler under `../rtl/` provides one shared operand path
for legacy GEMV and speculative GEMM. It:

- buffers K=1..4 token-major FP16 activation rows;
- reuses the buffered activation tile across all configured output rows;
- accepts each packed W4 group, zero point, and FP16 scale exactly once;
- unpacks low nibble first and emits signed five-bit `q-zero` lanes;
- replays the same registered weight metadata for K token positions;
- maintains stable operand payload and sidebands under output backpressure;
- reports the accepted weight-beat count for the bandwidth invariant;
- rejects K outside 1..4, invalid shapes/zero points, malformed `last`, and
  GEMV commands with K other than one.

## Vivado xsim 2022.2

Command:

```powershell
& .\kv260\speculative\rtl\run_p2_scheduler_xsim.ps1
```

Passing cases:

```text
P2_CASE_GO mode=0 K=1 rows=2 beats=3 weight_beats=6
P2_CASE_GO mode=1 K=1 rows=2 beats=3 weight_beats=6
P2_CASE_GO mode=1 K=2 rows=3 beats=3 weight_beats=9
P2_CASE_GO mode=1 K=3 rows=2 beats=5 weight_beats=10
P2_CASE_GO mode=1 K=4 rows=3 beats=5 weight_beats=15
P2_W4_WEIGHT_REUSE_SCHEDULER_GO
```

The first two cases have identical operand checksums, proving that GEMV K=1
and GEMM K=1 traverse the same datapath. For every case the accepted weight
count is `rows * beats`, not `K * rows * beats`. The testbench also injects
random operand-output stalls and asserts payload stability while stalled.

## Focused KV260 OOC synthesis

The final RTL was synthesized out of context for `xck26-sfvc784-2LV-c` with a
3.333 ns clock constraint. This was a module-only synthesis, not a full-system
implementation.

- synthesis errors: 0
- critical warnings: 0
- post-synthesis WNS: `1.255 ns`
- LUTs: `919` (`0.78%` of device)
- registers: `636` (`0.27%`)
- LUTRAM: `0`
- BRAM tiles: `28.5` (`19.79%`)
- DSPs: `0` (the shared FP16 MAC back-end is the next P2 sub-gate)
- synthesized DCP SHA256:
  `10da1e95282084776ea419dd15931ac8959f3ffb56a81ea22d830ffa89a7e68c`

The activation tile is intentionally bandwidth-banked: it provides one full
2048-bit FP16 activation group per cycle to match the existing 128-lane compute
path. The 28.5-BRAM result is therefore a bandwidth/area tradeoff to revisit
during P3 integration, where the existing four-bank organization may be reused.

## Remaining P2 gate

P2 is not yet GO. The signed W4 operands and scale must be connected to the
existing `fp16int5d4`, exponent scale-down, shared FP16 multiply/reduction, and
accumulation path. The combined output must then match the P1 reference for
K=1..4 before P3 transformer-scheduler integration begins.
