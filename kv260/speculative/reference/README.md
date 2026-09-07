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
