package top

import adapter.FlowMux
import quant.QuantWrapper
import rope._
import attn._
import spinal.core._
import spinal.lib._

import scala.language.postfixOps

class AttnSubMod(
                  busWidth: Int,
                  head: Int,
                  numOfCore: Int,
                  headDim: Int,
                  sqrtHeadDim: Int,
                  ropePoint: Int,
                  quantWidth: Int,
                  quantMaxIntFP16Init: Int,
                  maxToken: Int,

                  ropeTagMap: List[(Int, Int)],
                  quantTagMap: List[(Int, Int)],
                  softmaxTag: (Int, Int, Int),
                  qkMulTag: (Int, Int, Int),

                  mul_func: (Flow[Bits], Flow[Bits]) => Flow[Bits],
                  add_func: (Flow[Bits], Flow[Bits]) => Flow[Bits],
                  sub_func: (Flow[Bits], Flow[Bits]) => Flow[Bits],
                  div_func: (Flow[Bits], Flow[Bits]) => Flow[Bits],
                  acc_func: Flow[Fragment[Bits]] => Flow[Fragment[Bits]],

                  highPcs_mul_func: (Flow[Bits], Flow[Bits]) => Flow[Bits],
                  toInt_func: Flow[Bits] => Flow[Bits],
                  fromInt_func: Flow[Bits] => Flow[Bits],
                  lt_func: (Flow[Bits], Flow[Bits]) => Flow[Bool],
                  convert_func: Flow[Bits] => Flow[Bits],
                  exp_func: Flow[Bits] => Flow[Bits],

                  mix_mul_func: (Flow[Bits], Flow[Bits]) => Flow[Bits],
                  add_conv_func: (Flow[Bits], Flow[Bits]) => Flow[Bits],

                  fp32ToFp16Latency: Int,
                  fp16ToFp32: Flow[Bits] => Flow[Bits],
                  fp32ToFp16: Flow[Bits] => Flow[Bits],
                  fp32_lt_func: (Flow[Bits], Flow[Bits]) => Flow[Bool],
                  fp32_sub_func: (Flow[Bits], Flow[Bits]) => Flow[Bits],
                  fp32_acc_func: Flow[Fragment[Bits]] => Flow[Fragment[Bits]],
                  fp32_div_func: (Flow[Bits], Flow[Bits]) => Flow[Bits],
                  fp32_exp_func: Flow[Bits] => Flow[Bits]
                ) extends Component {

  val width = 16
  val headPerCore = head / numOfCore

  val io = new Bundle {
    val dotOut = slave(Flow(util.AxiFrame(Bits(width bits), userBit = 6)))
    val ropeOut = master(Flow(util.AxiFrame(Bits(width bits), userBit = 6)))
    val softmaxOut = master(Flow(Fragment(util.AxiFrame(Bits(width bits), userBit = 6))))
    val quantZero = master(Flow(Bits(quantWidth bits)))
    val quantScale = master(Flow(Bits(width bits)))
    val afterQuant = master(Flow(Fragment(Bits(quantWidth bits))))
  }

  val status = new Bundle {
    val token = in UInt (log2Up(maxToken) bits)
    val speculativeEnable = in Bool()
    val speculativeQuery = in UInt(2 bits)
    val speculativeCommitted = in UInt(log2Up(maxToken) bits)
  }

  val exp = new Bundle {
    val to = master(Flow(Bits(width bits)))
    val from = slave(Flow(Bits(width bits)))
  }

  val rope = new SerialRoPE(
    dim = headDim,
    points = ropePoint,
    numOfPort = 1,
    tagMap = ropeTagMap,
    lowPcs_mul_func = mul_func,
    highPcs_mul_func = highPcs_mul_func,
    add_func = add_func,
    toInt_func = toInt_func,
    fromInt_func = fromInt_func
  )

  //  val rope = new SerialRoPE32(
  //    dim = headDim,
  //    points = ropePoint,
  //    numOfPort = 1,
  //    tagMap = ropeTagMap,
  //    mix_mul_func = mix_mul_func,
  //    highPcs_mul_func = highPcs_mul_func,
  //    add_conv_func = add_conv_func,
  //    toInt_func = toInt_func,
  //    fromInt_func = fromInt_func
  //  )

  val quant = new QuantWrapper(
    busWidth = busWidth,
    quantWidth = quantWidth,
    headDim = headDim,
    maxIntFP16Init = quantMaxIntFP16Init,
    numOfPort = 2,
    tagMap = quantTagMap,
    lt_func = lt_func,
    sub_func = sub_func,
    div_func = div_func,
    convert_func = convert_func
  )

  val qk = new QKMul(
    width = width,
    dim = headDim,
    qkTag = qkMulTag,
    mul_func = mul_func,
    acc_func = acc_func,
    sqrtHeadDim = sqrtHeadDim
  )

  val softmax = new SerialSafeSoftmax(
    width = width,
    maxSeqLen = maxToken,
    numOfPort = 2,
    //    sqrtHeadDim = sqrtHeadDim,
    softmaxTag = softmaxTag,
    lt_func = lt_func,
    sub_func = sub_func,
    acc_func = acc_func,
    div_func = div_func,
    exp_func = exp_func
  )

  //  val softmax = new SerialSoftmaxFp32(
  //    maxSeqLen = maxToken,
  //    numOfPort = 2,
  //    softmaxTag = softmaxTag,
  //    fp32ToFp16Latency = fp32ToFp16Latency,
  //    fp16ToFp32 = fp16ToFp32,
  //    fp32ToFp16 = fp32ToFp16,
  //    lt_func = fp32_lt_func,
  //    sub_func = fp32_sub_func,
  //    acc_func = fp32_acc_func,
  //    div_func = fp32_div_func,
  //    exp_func = fp32_exp_func
  //  )

  val dotOutVldDly = Delay(io.dotOut.valid, 64, init = False)
  val dotOutDly = Delay(io.dotOut.payload, 64)

  val dotOutVldDly2 = Delay(dotOutVldDly, 64, init = False)
  val dotOutDly2 = Delay(dotOutDly, 64)

  quant.io.toBeQuant(0) << rope.io.output
  quant.io.toBeQuant(1).valid := dotOutVldDly
  quant.io.toBeQuant(1).payload := dotOutDly

  quant.io.quantZero >> io.quantZero
  quant.io.quantScale >> io.quantScale
  quant.io.afterQuant >> io.afterQuant

  softmax.io.input(0).valid := dotOutVldDly2
  softmax.io.input(0).payload := dotOutDly2

  softmax.io.input(1) << qk.io.output
  // SerialSafeSoftmax consumes the inclusive last score index. Legacy decode
  // therefore uses token, while candidate q sees committed+[0..q] and uses
  // committed+q as the inclusive index. QKMul and the downstream softmax-to-
  // AXPY path remain the production arithmetic datapath.
  val speculativeLast = status.speculativeCommitted + status.speculativeQuery.resize(log2Up(maxToken))
  softmax.io.seqLen.valid.set()
  softmax.io.seqLen.payload := Mux(status.speculativeEnable, speculativeLast, status.token).asBits
  softmax.io.output >> io.softmaxOut

  //  exp.to << softmax.exp.to
  //  exp.from >> softmax.exp.from

  rope.io.input(0) << io.dotOut
  rope.io.output >> qk.io.input
  rope.io.pos := status.token.resize(16).asBits
  //  rope.io.pos.clearAll()
  rope.io.output >> io.ropeOut
}
