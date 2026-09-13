package top

import core._
import spinal.core._
import spinal.lib._

import scala.language.postfixOps

class MulAddEngine(
                    width: Int,
                    bankLen: Int,
                    dotMaxFirstDim: Int,
                    axpyMaxFirstDim: Int,
                    mul_latency: Int,
                    add_latency: Int,
                    acc_latency: Int,
                    add_func: (Flow[Bits], Flow[Bits]) => Flow[Bits],
                    acc_func: Flow[Fragment[Bits]] => Flow[Fragment[Bits]],
                    mul_func_nonblock: (Flow[Bits], Flow[Bits]) => Flow[Bits],
                    mul_func_block: (Stream[Bits], Stream[Bits]) => Stream[Bits]
                  ) extends Component {

  val serialBit = width
  val parallelBit = width * bankLen

  val io = new Bundle {
    val wkvIn = slave(Stream(Bits(parallelBit bits)))
    val dotIn = slave(Stream(Bits(parallelBit bits)))
    val axpyIn = slave(Stream(Bits(serialBit bits)))
    val preScale = slave(Stream(Bits(serialBit bits)))
    val postScale = slave(Stream(Bits(serialBit bits)))
    val resAdd = slave(Stream(Bits(parallelBit bits)))
    val vecOut = master(Flow(util.AxiFrame(Bits(parallelBit bits), userBit = 6)))
    val scalarOut = master(Flow(util.AxiFrame(Bits(serialBit bits), userBit = 6)))
    val cfg = slave(Stream(Bits(32 bits)))
    val preCfgTag = out Bits(6 bits)
    val postCfgTag = out Bits(6 bits)
  }

  noIoPrefix()

  util.AxiStreamSpecRenamer(io.wkvIn)
  util.AxiStreamSpecRenamer(io.dotIn)
  util.AxiStreamSpecRenamer(io.axpyIn)
  util.AxiStreamSpecRenamer(io.preScale)
  util.AxiStreamSpecRenamer(io.postScale)
  util.AxiStreamSpecRenamer(io.resAdd)
  util.AxiStreamSpecRenamer(io.vecOut)
  util.AxiStreamSpecRenamer(io.scalarOut)
  util.AxiStreamSpecRenamer(io.cfg)

  val (toMul, toAdd) = StreamFork2(io.cfg)
  val toMulPipe = toMul.m2sPipe()
  val toAddPipe = toAdd.m2sPipe()

  val mul = new MulEngine(
    width = width,
    bankLen = bankLen,
    maxFirstDim = dotMaxFirstDim,
    inLineRam = true,
    mul_latency = mul_latency,
    mul_func_nonblock = mul_func_nonblock,
    mul_func_block = mul_func_block
  )

  val add = new AddEngineBk(
    width = width,
    bankLen = bankLen,
    maxFirstDim = axpyMaxFirstDim,
    mul_latency = mul_latency,
    acc_latency = acc_latency,
    add_latency = add_latency,
    inLineRam = true,
    mul_func = mul_func_nonblock,
    add_func = add_func,
    acc_func = acc_func
  )

  add.popVldNext := mul.popVldNext

  mul.io.wkvIn << io.wkvIn
  mul.io.dotIn << io.dotIn
  mul.io.axpyIn << io.axpyIn
  mul.io.scale << io.preScale
  // Legacy MulAddEngine has no speculative descriptor; retain the original
  // single-token activation loader and address path explicitly.
  mul.io.speculativeMode := False
  mul.io.speculativeK := U(1, 3 bits)
  mul.io.cfg.arbitrationFrom(toMulPipe)
  mul.io.cfg.data := toMulPipe.payload

  add.io.mulRes << mul.io.output
  add.io.resAdd << io.resAdd
  add.io.postScale << io.postScale
  add.io.cfg.arbitrationFrom(toAddPipe)
  add.io.cfg.data := toAddPipe.payload

  io.vecOut << add.io.vecOut.m2sPipe
  io.scalarOut << add.io.scalarOut.m2sPipe

  io.preCfgTag := mul.io.preCfgTag
  io.postCfgTag := add.io.postCfgTag
}

object MulAddEngine extends App {
  SpinalVerilog(new MulAddEngine(
    width = 16,
    bankLen = 32,
    dotMaxFirstDim = 32,
    axpyMaxFirstDim = 64,
    mul_latency = 6,
    add_latency = 6,
    acc_latency = 16,
    add_func = util.fp16add6.add,
    acc_func = util.fp16acc16.acc,
    mul_func_nonblock = util.fp16mul6.mul,
    mul_func_block = util.fp16mul7s.mul
  ))
}
