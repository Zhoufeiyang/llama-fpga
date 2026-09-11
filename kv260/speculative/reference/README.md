# Dense W4 numerical reference

`w4_reference.py` is the dependency-light numerical contract for the first
dual-mode projection kernel. It intentionally depends only on NumPy and does
not import AWQ, PyTorch, or the model-generation notebook.

It models:

- low-nibble-first W4 packing;
- page-local, split-major DMA layout and its inverse;
- the four-output-row dense block layout;
- FP16 scale and W4 zero-point placement;
- the exponent transformation implemented by `util.Fp16ScaleDown`;
- one-row GEMV and K-row short-sequence GEMM;
- the invariant that a verification batch consumes one packed weight stream.

Run the tests from this directory:

```powershell
python -m unittest -v test_w4_reference.py
```

The DMA helpers model the current KV260 geometry with 512-bit bus beats, four
lanes, and 8192-byte pages by default. `dma_join_pages` requires the unpadded
logical length and checks that the final page padding is zero, so an incorrect
DDR offset is rejected early.

Synthetic-vector success is only the P1 layout sub-gate. P1 is complete after
approved real packed blocks and RTL simulation outputs have also been checked.

`attention_reference.py` is the P4 causal-attention oracle. It compares dense
attention with committed/tentative tiled traversal for K=1..4, applies INT8
scale/zero dequantization, and uses stable max-subtracted softmax with a `-16`
clip. Run all P1 and P4 numerical tests with:

```powershell
python -m unittest discover -s kv260/speculative/reference -p 'test_*.py' -v
```

Verify the approved `llama0.bin` artifact and its first layer Q-head block:

```powershell
python verify_real_w4_block.py D:\path\to\llama0.bin --output evidence.json
```

The verifier checks the whole-image SHA256 before using the block, then proves
both logical W4 encode/decode identity and stored page-local DMA layout identity.

Run the independent SystemVerilog check of the approved real-vector prefix:

```powershell
& .\rtl\run_xsim.ps1
```

The testbench checks four-lane DMA reconstruction, W4 low-nibble-first order,
and little-endian FP16 scale placement. It is verification-only RTL and is not
part of the production P2 datapath.
