package attn

import spinal.core._

/** Focused P4 numerical shell around the production QKMul implementation.
  * Tags 1/2 select Q/K and tag 3 identifies the emitted score. The scale is
  * one so the xsim golden vector can isolate multiply/accumulate ordering.
  */
object QKMulP4Test extends App {
  SpinalVerilog(new QKMul(
    width = 16,
    dim = 128,
    qkTag = (1, 2, 3),
    mul_func = util.fp16mul6.mul,
    acc_func = util.fp16acc16.acc,
    sqrtHeadDim = 0x3c00
  ))
}
