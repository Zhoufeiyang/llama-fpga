package core

import spinal.core._
import spinal.lib._
import util.{Fp16ScaleDown, StreamFifoVldProbe}

import scala.language.postfixOps

class MulEngine(
                 width: Int,
                 bankLen: Int,
                 maxFirstDim: Int,
                 inLineRam: Boolean,
                 mul_latency: Int,
                 mul_func_nonblock: (Flow[Bits], Flow[Bits]) => Flow[Bits],
                 mul_func_block: (Stream[Bits], Stream[Bits]) => Stream[Bits],
                 speculativeMaxK: Int = 1
               ) extends Component {

  require(speculativeMaxK >= 1 && speculativeMaxK <= 4)

  val serialBit = width
  val parallelBit = width * bankLen

  case class Config() extends Bundle {

    val data = Bits(32 bits)
    data.setPartialName("")

    def firstDim = data.drop(16).take(8).asUInt

    def secondDim = data.take(16).asUInt

    def isAxpy = data.takeHigh(8).lsb
  }

  val io = new Bundle {
    val wkvIn = slave(Stream(Bits(parallelBit bits)))
    val dotIn = slave(Stream(Bits(parallelBit bits)))
    val axpyIn = slave(Stream(Bits(serialBit bits)))
    val scale = slave(Stream(Bits(serialBit bits)))
    val output = master(Stream(Bits(parallelBit bits)))
    val cfg = slave(Stream(Config()))
    // When asserted for a dot configuration, dotIn carries a token-major
    // K*B activation tile.  The output loop still uses B as its reduction
    // dimension; this control only changes the activation load/read address.
    val speculativeMode = in Bool()
    val speculativeK = in UInt(3 bits)
    val preCfgTag = out Bits (6 bits)
    val secondDim = out Bits(16 bits)
  }

  noIoPrefix()
  util.AxiStreamSpecRenamer(io.wkvIn)
  util.AxiStreamSpecRenamer(io.dotIn)
  util.AxiStreamSpecRenamer(io.axpyIn)
  util.AxiStreamSpecRenamer(io.scale)
  util.AxiStreamSpecRenamer(io.output)
  util.AxiStreamSpecRenamer(io.cfg)

  val cfgDeMux = new StreamDemux(Config(), 2)
  cfgDeMux.io.input << io.cfg
  cfgDeMux.io.select := io.cfg.payload.isAxpy.asUInt
  val toDotCfg = cfgDeMux.io.outputs(0)
  val toAxpyCfg = cfgDeMux.io.outputs(1)

  val activationDepth = maxFirstDim * speculativeMaxK
  val ram = new Bundle {
    val rdPort = util.MemRdPort(Bits(parallelBit bits), activationDepth)
    val wrPort = Flow(util.MemWrPort(Bits(parallelBit bits), activationDepth))
  }

  val mem = if (inLineRam) Mem(Bits(parallelBit bits), activationDepth) else null
  if (inLineRam) {
    mem.addAttribute("ram_style", "distributed")
    ram.rdPort.rsp := mem.readSync(enable = ram.rdPort.cmd.valid, address = ram.rdPort.cmd.payload)
    mem.write(enable = ram.wrPort.valid, address = ram.wrPort.address, data = ram.wrPort.data)
  }
  else {
    ram.rdPort.setAsMaster()
    ram.wrPort.setAsMaster()
  }

  val dotLogic = new Area {
    val cfg = toDotCfg
    val cfgPayload = cfg.payload

    val enInc = Bool()
    // Keep the wire cfg in its legacy 32-bit format while widening only the
    // internal speculative dot bound.
    val kSafe = UInt(3 bits)
    kSafe := io.speculativeK
    when(io.speculativeK === 0)(kSafe := 1)
    val secondDimCount = cfgPayload.secondDim.resize(18) + U(1, 18 bits)
    val speculativeSecondDimBound =
      (secondDimCount * kSafe.resize(18) - U(1, 21 bits)).resize(18)
    val secondDimBound = UInt(18 bits)
    secondDimBound := Mux(io.speculativeMode, speculativeSecondDimBound,
      cfgPayload.secondDim.resize(18))

    val (cnt, cntOvf) = util.LoopsCntGen.wireOvf(List(cfgPayload.firstDim, secondDimBound), enInc)
    val cntOvfReduce = cntOvf.reduce(_ & _)

    val flag = Bool().setAsReg().init(False)
    val notReadyFlag =  Bool().setAsReg().init(False)
    val inCnt = UInt((if (activationDepth <= 1) 1 else log2Up(activationDepth)) bits).setAsReg().init(0)
    val firstDimCount = cfgPayload.firstDim.resize(inCnt.getWidth) + U(1, inCnt.getWidth bits)
    val speculativeLoadBound = (firstDimCount * kSafe.resize(inCnt.getWidth) - U(1, (inCnt.getWidth + 3) bits)).resize(inCnt.getWidth)
    val inCntOvf = inCnt === Mux(io.speculativeMode, speculativeLoadBound, cfgPayload.firstDim.resize(inCnt.getWidth))
    inCnt.addAttribute("max_fanout", 100)
    cnt.head.addAttribute("max_fanout", 100)

    when(io.dotIn.fire) {
      inCnt := inCnt + 1
      when(inCntOvf) {
        inCnt := 0
        flag.set()
        notReadyFlag.set()
      }
    }

    val popPre = Event
    // Legacy GEMV overlaps RAM fill and compute.  A speculative tile must
    // not start the output loop after only token 0 is present: its read base
    // moves to token banks that are still being written.  Hold the output
    // loop until the complete K*B tile has raised flag, while preserving the
    // original overlap and timing in GEMV mode.
    popPre.valid := Mux(io.speculativeMode, flag, Mux(flag, True, cnt.head < inCnt))
    ram.wrPort.valid := io.dotIn.fire
    ram.wrPort.address := inCnt
    ram.wrPort.data := io.dotIn.payload

    val dotOut = Stream(Bits(parallelBit bits))
    dotOut.arbitrationFrom(popPre.m2sPipe())
    dotOut.payload := ram.rdPort.rsp
    ram.rdPort.cmd.valid := popPre.ready
    val tokenIndexWidth = if (speculativeMaxK <= 1) 1 else log2Up(speculativeMaxK)
    val tokenIndex = UInt(tokenIndexWidth bits).setAsReg().init(0)
    val tokenBase = UInt(inCnt.getWidth bits)
    tokenBase := 0
    when(io.speculativeMode) {
      tokenBase := (tokenIndex.resize(inCnt.getWidth) * firstDimCount).resize(inCnt.getWidth)
    }
    ram.rdPort.cmd.payload := (tokenBase + cnt.head.resize(inCnt.getWidth)).resize(log2Up(activationDepth))

    val enIncPipe = Bool()
    val (cntPipe, cntOvfPipe) = util.LoopsCntGen.wireOvf(List(cfgPayload.firstDim, secondDimBound), enIncPipe)
    val cntOvfReducePipe = cntOvfPipe.reduce(_ & _)
    enIncPipe := dotOut.fire
    val clrCondPipe = enIncPipe & cntOvfReducePipe

    val incCond = popPre.fire
    val clrCond = incCond & cntOvfReduce
    when(incCond && io.speculativeMode && cntOvf.head) {
      when(kSafe === 1 || tokenIndex === (kSafe - 1).resize(tokenIndexWidth)) {
        tokenIndex.clearAll()
      } otherwise {
        tokenIndex := tokenIndex + 1
      }
    }
    flag.clearWhen(clrCond)
    notReadyFlag.clearWhen(clrCondPipe)
    cfg.ready := clrCondPipe
    enInc := incCond

    val dotInReady = Bool().setAsReg().init(False)
    dotInReady.addAttribute("keep", "true")
    dotInReady.addAttribute("max_fanout", 100)
    dotInReady.setWhen(cfg.valid & ~notReadyFlag)
    dotInReady.clearWhen(io.dotIn.fire & inCntOvf)

    io.dotIn.ready := dotInReady
  }

  val axpyLogic = new Area {
    val enInc = Bool()
    val (cnt, cntOvf) = util.LoopsCntGen.wireOvf(List(toAxpyCfg.firstDim, toAxpyCfg.secondDim), enInc)
    val cntOvfReduce = cntOvf.reduce(_ & _)

    val inpHalt = io.axpyIn.continueWhen(toAxpyCfg.valid)
    val scaleHalt = io.scale.continueWhen(toAxpyCfg.valid)
    val inpRepeat = util.StreamRepeat(inpHalt, toAxpyCfg.firstDim)
    val scaledRes =  mul_func_block(inpRepeat, scaleHalt)
    val res = scaledRes.m2sPipe()
    res.valid.addAttribute("max_fanout", 100)

    val axpyOut = Stream(Bits(parallelBit bits))
    axpyOut.arbitrationFrom(res)
    axpyOut.payload := Repeat(res.payload, bankLen)

    val incCond = res.fire
    val clrCond = incCond & cntOvfReduce
    toAxpyCfg.ready := clrCond
    enInc := incCond
  }

  val secondDim = toAxpyCfg.secondDim.asBits
  io.secondDim := secondDim
  io.preCfgTag := toAxpyCfg.payload.asBits.takeHigh(6)

  val mux = new StreamMux(Bits(parallelBit bits), 2)
  mux.io.inputs(0) << dotLogic.dotOut
  mux.io.inputs(1) << axpyLogic.axpyOut
  mux.io.select := axpyLogic.axpyOut.valid.asUInt

  val join = StreamJoin(io.wkvIn.toEvent(), mux.io.output.toEvent())

  val act = Flow(Vec(Bits(serialBit bits), bankLen))
  val wkv = Flow(Vec(Bits(serialBit bits), bankLen))
  val wkvScaleDown = io.wkvIn.payload
  act.valid := join.fire
  wkv.valid := join.fire
  act.payload := mux.io.output.payload.subdivideIn(bankLen slices)
  wkv.payload := wkvScaleDown.subdivideIn(bankLen slices)

  val mul = new core.Vec2to1(serialBit, bankLen, mul_latency, mul_func_nonblock)
  mul.io.in0 << act
  mul.io.in1 << wkv

  val fifo = new StreamFifoVldProbe(Bits(parallelBit bits), 32, forFMax = true)
  val popVldNext = fifo.io.popVldNext.toIo()
  fifo.io.push.valid := mul.io.res.valid
  fifo.io.push.payload := mul.io.res.payload.asBits

  io.output << fifo.io.pop

  val ready = Bool().setAsReg().init(False)
  ready.addAttribute("max_fanout", 100)
  ready := fifo.io.availability >= 8
  join.ready := ready
}
