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
