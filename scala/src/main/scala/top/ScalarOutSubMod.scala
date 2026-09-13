package top

import adapter.{FlowGate, TagMap}
import mlp.{UGMul, ZeroFilter}
import spinal.core._
import spinal.lib._
import util.URAM16x16384Fifo

import scala.language.postfixOps

class ScalarOutSubMod(
                       mlpDim: Int,
                       width: Int,
                       actLatency: Int,
                       siluLatency: Int,
                       index2UgTag: (Int, Int, Int),
                       ugMulTag: (Int, Int, Int),
                       filterTagMap: List[(Int, Int)],
                       tagSeqLenMap: List[(Int, Int)],
                       axpyInTag: (Int, Int, Int),
                       act_func: Flow[Bits] => Flow[Bits],
                       mul_func: (Flow[Bits], Flow[Bits]) => Flow[Bits],
                       lt_func: (Flow[Bits], Flow[Bits]) => Flow[Bool]
                     ) extends Component {

  val io = new Bundle {
    val p2sOut = slave(Flow(util.AxiFrame(Bits(width bits), userBit = 6)))
    val allReduceOut = slave(Flow(util.AxiFrame(Bits(width bits), userBit = 6)))
    val softmaxOut = slave(Flow(Fragment(util.AxiFrame(Bits(width bits), userBit = 6))))
    val scalarOut = master(Stream(Bits(width bits)))

    val allGatherIndexIn = slave(Flow(Fragment(util.AxiFrame(Bits(16 bits), userBit = 6))))
    val zfIndexOut = master(Flow(Fragment(util.AxiFrame(Bits(16 bits), userBit = 6))))
    val gateIndexOut = master(Flow(Fragment(util.AxiFrame(Bits(16 bits), userBit = 6))))
    val ugIndexOut = master(Flow(Fragment(util.AxiFrame(Bits(16 bits), userBit = 6))))
  }

  //  val filter = new ZeroFilter(
  //    width = width,
  //    numOfPort = 2,
  //    tagMap = filterTagMap,
  //    tagSeqLenMap = tagSeqLenMap,
  //    lt_func = lt_func
  //  )

  val ug = new UGMul(
    mlpDim = mlpDim,
    width = width,
    index2UgTag = index2UgTag,
    ugTag = ugMulTag,
    actLatency = actLatency,
    siluLatency = siluLatency,
    lt_func = lt_func,
    act_func = act_func,
    mul_func = mul_func
  )


  val scalarFifo = new URAM16x16384Fifo()

  val status = ug.status.toIo()
  // Terminal of the real G/U elementwise activation stream. The speculative
  // projection sequencer uses this event as its U-to-D barrier.
  val mlpActivationDone = out Bool()
  mlpActivationDone := ug.io.ugOut.valid && ug.io.ugOut.last
  val silu = ug.silu.toIo()

  //  filter.io.input(0) << io.p2sOut
  //  filter.io.input(1) << io.allReduceOut
  //
  //  io.zfIndexOut << filter.io.index.m2sPipe
  io.zfIndexOut.clearAll()
  io.gateIndexOut << ug.io.gateIndexOut.m2sPipe
  io.ugIndexOut << ug.io.ugIndexOut.m2sPipe

  ug.io.predIndexIn << io.allGatherIndexIn
  ug.io.allReduceOut << io.allReduceOut

  val ug2Axpy = FlowGate.fragment(ug.io.ugOut, List(axpyInTag._1))
  val softmax2Axpy = FlowGate.fragment(io.softmaxOut, List(axpyInTag._2))
  //  val gtZero2Axpy = FlowGate.fragment(filter.io.gtZero, List(axpyInTag._3))
  val (axpyInMux, axpyErr) = adapter.FlowMux(Vec(ug2Axpy, softmax2Axpy))
  val axpyIn = axpyInMux.m2sPipe

  util.AxiStreamSpecRenamer(axpyIn)

  scalarFifo.io.push.valid := axpyIn.valid
  scalarFifo.io.push.fragment := axpyIn.fragment
  scalarFifo.io.push.last := axpyIn.last
  io.scalarOut.arbitrationFrom(scalarFifo.io.pop)
  io.scalarOut.payload := scalarFifo.io.pop.fragment
}
