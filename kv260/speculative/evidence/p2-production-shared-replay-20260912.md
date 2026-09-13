# P2 production shared GEMV/GEMM replay front-end — 2026-09-12

## Scope and gate

This milestone integrates the weight/activation replay front-end into the
production `MulAddSGNew` path.  It does not change `GenMemCmdLenAlign` or the
runtime controller, and it does not claim projection completion from the
front-end's `replayDone` pulse.

`P2 production replay schedule: GO`

`P2 production full numerical/vendor source gate: GO (2026-09-13)`

The production source now composes the replay front-end, widened internal row
bound, shared Xilinx-FP16 multiplier/reduction engine, post-scale replay, and
the existing FP32 accumulation boundary.  Placed timing remains a P7 gate.

## Implemented production path

- `top.SpeculativeGemmReplay` sits directly on the real `wkvIn` and `dotIn`
  streams inside `MulAddSGNew`.
- In legacy mode the streams and configuration are transparent ready/valid
  pass-throughs.
- In GEMM mode the front-end sends a token-major K×B activation tile to the
  existing MulEngine RAM, buffers one B-beat weight row once, and emits that
  row K times.  No K copies of the multiplier/reduction/FP32 accumulation
  array are instantiated.
- `MulAddEngineNew` keeps `firstDim=B-1` on both MulEngine and AddEngine and
  derives an internal 18-bit `secondDim=K*rows-1` without changing the 32-bit
  cfg ABI.  This covers LM-head bounds through 127999 for K=4. MulEngine's speculative loader
  accepts the K×B activation tile into an expanded RAM and selects
  `(secondDim mod K)*B+firstDim` for each output beat.  Thus AddEngine's
  existing `last` boundary remains exactly every B products, producing
  independent token/row accumulations.
- Only the speculative `MulEngine` instance has a statically enlarged
  `4*dotMaxFirstDim` activation address space; the multiplier/reduction and
  FP32 accumulation structures remain shared.
- The B post-scale words associated with a physical weight row are buffered
  once and independently replayed K times.  The scale stream may drain later
  than the weight stream, so FP32 pipeline latency cannot overwrite a scale.
- Invalid K/shape descriptors are consumed into a sticky error state before
  reaching the downstream cfg input.  The effective engine mode is frozen at
  descriptor acceptance and cannot change in flight.
- Passive counters expose `speculativeInputWeightBeats` and
  `speculativeLogicalOperandReplays` from `MulAddSGNew`; the parent DataPath
  can route these to its performance-counter window.

## Focused verification

The integer schedule/numerical checker was run as:

```text
python kv260/speculative/reference/verify_production_replay_schedule.py \
  --output kv260/speculative/build/p2-production-replay-schedule-20260912.json
```

Observed result for rows=3, B=5, lanes=8:

```text
K=1: input_weight_beats=15, logical_operand_replays=15, independent_results=3
K=2: input_weight_beats=15, logical_operand_replays=30, independent_results=6
K=3: input_weight_beats=15, logical_operand_replays=45, independent_results=9
K=4: input_weight_beats=15, logical_operand_replays=60, independent_results=12
```

All expected token/row integer dot products matched exactly.  The checker also
verified the scalar stream values and `last` indices (`B-1, 2B-1, ...`) for
every K.  The generated standalone replay RTL was then run in Vivado XSim:

```text
P2_PROD_REPLAY_K1_GO input_weight_beats=4 logical_replays=4 outputs=4
P2_PROD_REPLAY_K2_GO input_weight_beats=4 logical_replays=8 outputs=8
P2_PROD_REPLAY_K3_GO input_weight_beats=4 logical_replays=12 outputs=12
P2_PROD_REPLAY_K4_GO input_weight_beats=4 logical_replays=16 outputs=16
P2_PRODUCTION_REPLAY_FRONTEND_GO K1_TO_K4_WEIGHT_INDEPENDENT=1 SCALE_REPLAY=1 TOKEN_ORDER=1
```

The actual `MulAddEngineNew` Xilinx floating-point simulation produced:

```text
P2_PROD_DATAPATH_K1_GO scalar_outputs=4 independent_results=2 last_every_B=1
P2_PROD_DATAPATH_K2_GO scalar_outputs=8 independent_results=4 last_every_B=1
P2_PROD_DATAPATH_K3_GO scalar_outputs=12 independent_results=6 last_every_B=1
P2_PROD_DATAPATH_K4_GO scalar_outputs=16 independent_results=8 last_every_B=1
P2_PRODUCTION_DATAPATH_ENGINE_GO K1_TO_K4_NUMERICAL=1 LAST_BOUNDARY=1
```

The checker additionally exercised LM-head row bounds
`31999/63999/95999/127999`; the K=3/4 cases are intentionally above 16 bits.
`runMain top.P2ProductionDatapathElab` and the complete production
`runMain top.EdgeLLMInst` both completed with zero elaboration errors.  The
testbench ready/valid drivers sample acceptance on clock edges, preventing the
duplicate-transfer artifact found in the first testbench revision.
