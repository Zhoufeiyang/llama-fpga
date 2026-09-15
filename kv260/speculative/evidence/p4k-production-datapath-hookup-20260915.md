# P4-K production DataPath hookup — 2026-09-15

`P4-K production command/data/metadata hookup sub-gate: GO`

`P4 final hardware/board gate: IN PROGRESS`

## Delivered integration

The production `DataPath` now instantiates `P4KvFetchFrontend` and
`P4DataMoverBridge`. Legacy and P4 MM2S commands share the same 72-bit logical
boundary before `AddressRemap`; returned 512-bit data and aggregate status are
demultiplexed by independent owner FIFOs. P4 K/V values enter the existing
cache-tagged INT8 conversion path, while exact packed 32-bit metadata slices
bypass the legacy token-zero heuristic and feed the existing K/V scale/zero
streams. K and V addresses use the command generator's exported current
layer/head base, so both paths have identical memory-map lineage.

The real transformer sequencer attention request starts P4 exactly once after
the preceding MM2S epoch drains. QK tiles retire on reduced-score events. V
tile transport completion only advances input streaming; the controller now
waits separately for the final shared AXPY vector terminal across K lanes
before releasing the transformer attention barrier. Transport/owner faults
enter both the P4 controller fault path and the AXI-Lite error status.

## Acceptance evidence

Full KV260 Scala compile and `top.EdgeLLMInst` elaboration completed with zero
errors. Generated RTL contains the P4 frontend, owner bridge, exported
attention request, and current attention-base wiring. Expected inferred-memory
warnings remain; no synthesis or implementation was run.

Focused generated-RTL xsim:

```text
P4I_KV_FETCH_FRONTEND_GO OWNER_SERIAL=1 REPLAY_NO_DDR=1 BACKPRESSURE=1 DONE_IDENTITY=1
P4J_DATAMOVER_BRIDGE_GO P4_PRIORITY=1 FRAME_STABLE=1 OWNER_DEMUX=1 BACKPRESSURE=1
P4B_TILE_REUSE_K1 qk_ops=4 v_ops=4 qk_ddr=3 v_ddr=3
P4B_TILE_REUSE_K2 qk_ops=8 v_ops=8 qk_ddr=3 v_ddr=3
P4B_TILE_REUSE_K3 qk_ops=12 v_ops=12 qk_ddr=3 v_ddr=3
P4B_TILE_REUSE_K4 qk_ops=16 v_ops=16 qk_ddr=3 v_ddr=3
P4_SPINAL_CONTROLLER_GO K1_TO_K4=1 DDR_TILE_READS_K_INDEPENDENT=1 V_BATCH_TERMINAL=1 BUFFER_FAULT=1
```

## Scope

This closes source/elaboration and focused RTL behavior, not physical timing or
board execution. The final claim still requires the single planned Vivado
build, route/timing audit, matching ELF/BOOT packaging, and KV260 measurements.
