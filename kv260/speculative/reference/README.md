# Dense W4 numerical reference

`w4_reference.py` is the dependency-light numerical contract for the first
dual-mode projection kernel. It intentionally depends only on NumPy and does
not import AWQ, PyTorch, or the model-generation notebook.

It models:

- low-nibble-first W4 packing;
- the four-output-row dense block layout;
- FP16 scale and W4 zero-point placement;
- the exponent transformation implemented by `util.Fp16ScaleDown`;
- one-row GEMV and K-row short-sequence GEMM;
- the invariant that a verification batch consumes one packed weight stream.

Run the tests from this directory:

```powershell
python -m unittest -v test_w4_reference.py
```

Synthetic-vector success is only the P1 layout sub-gate. P1 is complete after
approved real packed blocks and RTL simulation outputs have also been checked.
