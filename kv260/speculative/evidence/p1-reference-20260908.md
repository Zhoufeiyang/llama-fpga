# P1 packed-W4 reference evidence — 2026-09-08

## Result

`P1: GO`

All three P1 evidence classes pass: synthetic reference vectors, an approved
real packed projection block, and an independent RTL layout simulation.

## Approved source artifact

- File: `llama0.bin`
- Size: `2,109,472,768` bytes
- SHA256: `45bb125d50787badcc6df85fd99ce499ea3a43e160dcee4d736f6fa1b5c2c093`
- Manifest source: checked-in `python/gen_bin.ipynb`

## Synthetic numerical contract

Command:

```powershell
python -m unittest -v kv260.speculative.reference.test_w4_reference
```

Result: `12/12 PASS`.

The tests cover low-nibble-first W4 packing, fixed lane order, page-local DMA
round trips and padding rejection, dense scale/zero/weight layout, the FP16
exponent edit, `K=1..4` GEMM equivalence to repeated GEMV, and K-independent
target-weight traffic.

## Real packed block

Command:

```powershell
python kv260/speculative/reference/verify_real_w4_block.py D:\JSA paper\work\llama0.bin
```

Result: `P1_REAL_W4_BLOCK_GO`.

- Block: `layer0.attention.head0.q_proj`
- File offset: `262,152,192`
- Stored extent: `278,528` bytes
- Logical dense-W4 extent: `272,384` bytes
- Stored SHA256: `bc032a9bbd743fb6ef33e17e773576d5f48831ca24c7d0b08c612575d48fb68a`
- Logical SHA256: `ffb3064b4183dec833b031e8f42ad20728fc42f6cbed04f35ba41ae371424654`
- Matrix: `128 x 4096`, group size `128`
- Quantized weights: `[0, 15]`
- Zero points: `[0, 15]`
- FP16 scales: finite, range `[0.0242767333984375, 3.033203125]`
- Logical W4 decode/re-encode: byte-identical
- Stored four-lane DMA reconstruction: byte-identical

## RTL layout simulation

Command:

```powershell
& kv260/speculative/reference/rtl/run_xsim.ps1
```

Tool: Vivado Simulator 2022.2, build 3671981.

Result:

```text
P1_W4_LAYOUT_SIM_GO bytes=128 first_zero_pair=4,8 first_scale_bits=30a9
```

The verification-only SystemVerilog testbench uses the first two stored
512-bit beats of the approved real block. It independently reconstructs the
four split-major lanes and checks the golden logical bytes, low-nibble-first
zero-point order, and little-endian FP16 scale placement.

## Scope

This gate validates the packed-W4 numerical and byte-layout contract. The RTL
testbench is not a production kernel and does not count as starting P2. P2 may
begin only after the overall P0 gate is GO.
