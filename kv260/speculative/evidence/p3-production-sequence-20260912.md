# P3 production projection sequencing — 2026-09-12

## Gate result

`P3 production sequencing contract: GO`

`P3 production GenMem command-gating hookup: BLOCKED / OPEN`

No full-design synthesis, implementation, bitstream generation, or board
programming was run.

## Implemented control contract

`top.SpeculativeProjectionSequencer` is a SpinalHDL production control block.
It expands one target launch into the ordered projection sequence:

```text
Q -> K -> V -> attentionDone -> O -> G -> U -> mlpActivationDone -> D
  -> (next layer Q...) -> LM head
```

The controller uses the actual `cfgGen.LLaMA2_7B.param` weight-stream tags:

```text
Q=4 K=5 V=7 O=11 G=15 U=16 D=17 LM_HEAD=19
```

Each output descriptor carries the latched GEMV/GEMM mode and K. Physical
`rows` and `beatsPerRow` are selected from the target matrix geometry and do
not depend on K. Descriptor completion must return the matching projection tag
and layer ID; a mismatch is a sticky fault. Legacy mode accepts only K=1.

The controller also reports the sum of `rows * beatsPerRow` for accepted
descriptors. This is a control-plane witness for one physical weight pass per
matrix, independent of the number of candidate rows.

## Focused verification

The small-geometry generated RTL was compiled and simulated with Vivado 2022.2
XSim:

```text
P3_PRODUCTION_SEQUENCE_CASE_GO K=1 mode=1 projections=15 weight_beats=176
P3_PRODUCTION_SEQUENCE_CASE_GO K=2 mode=1 projections=15 weight_beats=176
P3_PRODUCTION_SEQUENCE_CASE_GO K=3 mode=1 projections=15 weight_beats=176
P3_PRODUCTION_SEQUENCE_CASE_GO K=4 mode=1 projections=15 weight_beats=176
P3_PRODUCTION_SEQUENCE_IDENTITY_FAULT_GO WRONG_TAG=5 EXPECTED_TAG=4 STICKY=1
P3_PRODUCTION_SEQUENCE_GO K1_TO_K4=1 ORDER=Q,K,V,ATTN,O,G,U,MLP,D,LM_HEAD WEIGHT_PASS_K_INDEPENDENT=1 LEGACY_K_GT1_REJECT=1
```

Reproduction:

```powershell
# from D:\JSA paper\llama-fpga\scala
$env:SBT_GLOBAL_BASE='D:\JSA paper\work\codex_tools\sbt-global'
$env:SBT_IVY_HOME='D:\JSA paper\work\codex_tools\ivy2'
$env:COURSIER_CACHE='D:\JSA paper\work\codex_tools\coursier'
& 'F:\Xilinx2022\Vitis\2022.2\tps\win64\jre11.0.11_9\bin\java.exe' `
  '-Dsbt.server.autostart=false' `
  '-Dsbt.global.base=D:\JSA paper\work\codex_tools\sbt-global' `
  '-Dsbt.ivy.home=D:\JSA paper\work\codex_tools\ivy2' `
  '-Dcoursier.cache=D:\JSA paper\work\codex_tools\coursier' `
  '-jar' 'D:\JSA paper\work\codex_tools\sbt-launch-1.13.0.jar' `
  'runMain top.SpeculativeProjectionSequencerTest'
& .\kv260\speculative\rtl\run_p3_production_sequence_xsim.ps1
```

## Open architectural boundary

The existing `GenMemCmdLenAlign` command generator still bundles the Q/K/V
and cache transfers into a legacy command stream and emits the later O/G/U/D
stream from its internal `select` state. It has no production descriptor
completion identity output and no barrier-gated command boundary between the
V weight transfer and O, or between U and D. Merely feeding the new sequence
descriptors into the current descriptor register would therefore be unsafe:
the DMA can advance to the next tagged segment before the next descriptor is
accepted, and the current command stream cannot pause at the required
attention/MLP barriers.

Consequently this change intentionally does not claim that the new controller
is physically wired into the current DMA command mux. Closing this gate needs
one follow-up GenMem command-path change: hold the AXI-MM2S command/data
boundary until the sequencer accepts the matching tag, expose completion
`{tag,layer}` from the command generator, and split/gate the QKV and MLP
command groups around the two barrier handshakes. That work must be coordinated
with the current P2 datapath and P4 completion adapter before any full build.
