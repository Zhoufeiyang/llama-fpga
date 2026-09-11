# P4-A production softmax numerical gate — 2026-09-11

## Verdict

`P4-A production softmax numerical sub-gate: GO`

`P4-A complete attention numerical gate: IN PROGRESS`

The test elaborates the checked-in `SerialSafeSoftmaxTest` SpinalHDL component
and simulates it with the actual Vivado 2022.2 `fp16lt0`, `fp16sub8`,
`fp16ex12`, `fp16acc16`, and `fp16div12` IP models. It does not substitute a
behavioral real-number softmax.

## Acceptance result

Four causal rows use inclusive last indices 0, 1, 2, and 3. Zero logits make
the exact expected distribution independently checkable as uniform FP16.

```text
P4A_SOFTMAX_Q0 visible=1 probability=3c00
P4A_SOFTMAX_Q1 visible=2 probability=3800
P4A_SOFTMAX_Q2 visible=3 probability=3555
P4A_SOFTMAX_Q3 visible=4 probability=3400
P4A_VENDOR_SOFTMAX_GO K1_TO_K4=1 FINITE=1 NORMALIZED=1
```

Every output is finite, every row terminates at the programmed causal length,
and each uniform row sums to one within the plan threshold. Artifact hashes:

- generated `SerialSafeSoftmaxTest.v` (16,563 bytes):
  `dee8d2fab018e4e0f199ee9f3b631182780ac68c71343d84badd6476b4588333`
- xsim `simulate.log` (1,547 bytes):
  `c4df5e84e548901cfebbd0cd1c7e1f650867b2c1ed80f624ba75cc4fd3a6bb6f`

P4-A is not yet complete because the same vendor-IP gate must cover QK dot
products and the final V weighted accumulation against the NumPy oracle.
