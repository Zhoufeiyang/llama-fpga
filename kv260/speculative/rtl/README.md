# P2 dual-mode W4 projection RTL

`p2_w4_weight_reuse_scheduler.sv` is the production-oriented operand front-end
for the shared GEMV/GEMM datapath. It uses the KV260 projection geometry by
default:

- 128 W4 weights per 512-bit packed beat;
- 32 beats per 4096-element activation row;
- K = 1..4;
- up to 4096 output rows.

The scheduler loads a token-major FP16 activation tile once. For every output
row and input group it accepts one packed W4 beat, one zero point, and one FP16
scale, then emits K operand records while holding the weight metadata. Thus the
accepted packed-weight count is `rows * beatsPerRow`, independent of K.

`cfg_mode=0` is legacy GEMV and requires K=1. `cfg_mode=1` is speculative GEMM
and accepts K=1..4. Both modes use the same memory, unpack/subtract logic, and
operand stream. The downstream P2 stage connects the signed `q-zero` lanes to
the existing `fp16int5d4` plus `Fp16ScaleDown` semantics and the shared FP16 MAC
array.

Run the focused tests from the repository root:

```powershell
& .\kv260\speculative\rtl\run_p2_scheduler_xsim.ps1
& .\kv260\speculative\rtl\run_p2_scheduler_ooc.ps1
```

The first command is self-checking and fails unless the final GO marker is
present. The second command synthesizes only this module for the KV260 part; it
does not rebuild the full block design or create a board bitstream.

P3 adds `p3_batched_projection_controller.sv`, which sequences the Llama2-7B
Q/K/V/O/G/U/D and LM-head projections and translates every descriptor to the
P2 configuration interface:

```powershell
& .\kv260\speculative\rtl\run_p3_scheduler_xsim.ps1
& .\kv260\speculative\rtl\run_p3_scheduler_ooc.ps1
```

P4-A causal KV tile control is checked with:

```powershell
& .\kv260\speculative\rtl\run_p4_tile_xsim.ps1
& .\kv260\speculative\rtl\run_p4_tile_ooc.ps1
```

P4-B sequences QK tiles, stable softmax, and V accumulation for every causal
candidate query. Its focused checks are:

```powershell
& .\kv260\speculative\rtl\run_p4b_attention_xsim.ps1
& .\kv260\speculative\rtl\run_p4b_attention_ooc.ps1
```

The production `SerialSafeSoftmax` vendor-IP path is checked for causal rows
K=1..4 with:

```powershell
& .\kv260\speculative\rtl\run_p4a_vendor_softmax.ps1
```

The production QK dot product and focused V weighted-accumulation arithmetic
are checked with the actual Xilinx FP16 multiplier and accumulator models:

```powershell
& .\kv260\speculative\rtl\run_p4a_qk_v_vendor.ps1
```

P5-A adds direct-to-future-slot tentative KV writes, pointer-only commit and
rollback, and 64-byte metadata-line read-modify-write preservation:

```powershell
& .\kv260\speculative\rtl\run_p5_xsim.ps1
```

After elaborating `top.AxiLiteCtrlP5Test`, validate the production AXI-Lite
pointer-control registers with:

```powershell
& .\kv260\speculative\rtl\run_p5b_axilite_xsim.ps1
```

After elaborating `cfgGen.SpeculativeKvPositionP5CTest`, validate the production
KV read/write position selector with:

```powershell
& .\kv260\speculative\rtl\run_p5c_position_xsim.ps1
```

After elaborating `attn.KvScaleZeroPackerP5Test`, validate retained-line replay
at the production 15/16 and 31/32 metadata boundaries with:

```powershell
& .\kv260\speculative\rtl\run_p5e_metadata_xsim.ps1
```

P6-A checks greedy mismatch, correction/bonus output, commit delta,
backpressure, and timeout behavior with:

```powershell
& .\kv260\speculative\rtl\run_p6_acceptance_xsim.ps1
```

After elaborating `top.AxiLiteCtrlP5Test`, validate production candidate and
target-result retention with:

```powershell
& .\kv260\speculative\rtl\run_p6b_axilite_xsim.ps1
```

The AXI-Lite candidate IDs can also be replayed as an ordered, backpressurable
token Stream.  Elaborate `top.SpeculativeTokenIngressTest`, then run:

```powershell
& .\kv260\speculative\rtl\run_speculative_token_ingress_xsim.ps1
```

Candidate q is removed from the legacy routing tag and retained across the
complete active token transaction. Elaborate `top.SpeculativeTokenContextTest`,
then run:

```powershell
& .\kv260\speculative\rtl\run_speculative_token_context_xsim.ps1
```

The production command mux collects exactly K accepted embedding commands at
the start of an epoch. Elaborate
`cfgGen.SpeculativeBatchCommandCollectorTest`, then run:

```powershell
& .\kv260\speculative\rtl\run_p3c_batch_collector_xsim.ps1
```

The production descriptor grant and automatic 32-layer sequence are checked
with:

```powershell
& .\kv260\speculative\rtl\run_p3d_projection_command_gate_xsim.ps1
& .\kv260\speculative\rtl\run_p3e_axilite_auto_sequence_xsim.ps1
```

Arithmetic-terminal descriptor retirement is checked with:

```powershell
& .\kv260\speculative\rtl\run_p3f_projection_retirement_xsim.ps1
```
### P4-G physical KV tile requester

`run_p4g_kv_tile_requester_xsim.ps1` validates the generated
`attn.P4KvTileRequester`: one DDR fetch fills the selected ping/pong BRAM,
`fetch=0` replays without another command, output backpressure preserves every
beat, and completion metadata matches the accepted request.
