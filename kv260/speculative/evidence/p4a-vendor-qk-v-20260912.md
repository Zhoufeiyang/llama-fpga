# P4-A production QK and V numerical validation — 2026-09-12

## Verdict

`P4-A production QK numerical sub-gate: GO`

`P4-A V weighted-accumulation numerical sub-gate: GO`

`P4: GO`

No full synthesis, implementation, bitstream generation, or board programming
was run. P4 closes by composing the previously accepted causal scheduler and
NumPy oracle, generated production connection proof, production softmax vendor
simulation, and the focused QK/V vendor-IP numerical simulation recorded here.

## Tested arithmetic

`QKMulP4Test.scala` elaborates the production `QKMul` implementation at head
dimension 128. The test uses the actual `fp16mul6` and `fp16acc16` Xilinx
Floating-Point IP models. The scale is fixed to FP16 one so the expected dot
product is independently auditable.

The V shell applies the same multiply-then-accumulate arithmetic order with the
same two vendor IP models. It is a focused arithmetic test, not a replacement
for the generated-top structural proof of the production softmax-to-V-AXPY
connection.

For causal visible lengths one through four, unit Q/K vectors produce score
one. Uniform probabilities multiplied by unit V produce weighted sum one,
allowing one FP16 ULP around one for the three-term accumulation.

## xsim result

```text
P4A_QK_Q0 score=3c00
P4A_V_Q0 weighted_sum=3c00
P4A_QK_Q1 score=3c00
P4A_V_Q1 weighted_sum=3c00
P4A_QK_Q2 score=3c00
P4A_V_Q2 weighted_sum=3bff
P4A_QK_Q3 score=3c00
P4A_V_Q3 weighted_sum=3c00
P4A_VENDOR_QK_V_GO K1_TO_K4=1 FINITE=1
```

All results are finite. K=1 is bit-exact at both QK and V boundaries. The K=3
result differs from one by one FP16 ULP because `1/3` is not exactly
representable; it is within the acceptance bound and matches the expected
sequential FP16 accumulation behavior.

## Reproduction

```powershell
& .\kv260\speculative\rtl\run_p4a_qk_v_vendor.ps1
```

Generate `QKMul.v` from the checked-in `attn.QKMulP4Test` entry point before
running the command. The script then compiles the vendor IP simulation models,
runs a self-checking xsim testbench, and requires the final GO marker.

Artifact hashes are frozen in `p4-artifact-manifest-20260911.json`. The accepted
P4 claim is limited to RTL/reference/vendor-IP simulation and generated-source
integration. Board validation remains a later stage.
