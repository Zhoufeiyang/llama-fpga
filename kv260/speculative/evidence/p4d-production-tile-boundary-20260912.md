# P4-D production attention tile-control boundary — 2026-09-12

## Gate status

`P4-D production tile-control boundary: GO`

`P4-B physical KV tile reuse hookup: IN PROGRESS`

No Vivado synthesis or implementation was run.

## Implemented source path

`P4AttentionPhaseController` is instantiated inside production `AttnSubMod`.
For K=1..4 it issues 64-token committed-prefix tiles, then the causal
tentative range `0..q`, launches softmax over `committed+q+1`, and replays the
same ranges for the V weighted-accumulation phase. Request identity
(phase/source/query/start/buffer) is locked until matching completion; invalid
or unsolicited completion enters a sticky fault.

The adapter exposes taps from the real production QK, softmax, and
softmax-to-V-AXPY streams. It remains quiescent when speculative mode is off,
so the legacy attention path is unchanged.

## Verification

- Full Scala compilation: success.
- Focused `attn.P4AttentionPhaseControllerTest` elaboration and Verilog
  generation: success.
- Production `top.EdgeLLMInst` elaboration with this instantiated controller:
  success.

This is an explicit production boundary, not a physical reuse claim. The tile
requests still require connections to the real DDR response manager,
softmax-complete event, and V-AXPY tile-complete event before P4-B production
can be marked GO or KV-read traffic can be measured.
