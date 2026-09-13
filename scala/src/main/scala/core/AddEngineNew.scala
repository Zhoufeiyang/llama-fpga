package core

import spinal.core._
import spinal.lib._
import util.Fp16ScaleUp

import scala.language.postfixOps

class AddEngineNew(
                    width: Int,
                    bankLen: Int,
                    maxFirstDim: Int,
                    mul_latency: Int,
                    add_latency: Int,
                    acc_latency: Int,
                    inLineRam: Boolean,
                    mul_func: (Flow[Bits], Flow[Bits]) => Flow[Bits],
                    add_func: (Flow[Bits], Flow[Bits]) => Flow[Bits],
                    acc_func: Flow[Fragment[Bits]] => Flow[Fragment[Bits]]
                  ) extends Component {

  val serialBit = width
  val parallelBit = width * bankLen
  val minFirstDim = add_latency + 2
  val reduceLatency = log2Up(bankLen) * add_latency

  require(isPow2(minFirstDim))

  case class Config() extends Bundle {

    val data = Bits(32 bits)
    data.setPartialName("")

    def firstDim = data.drop(16).take(8).asUInt

    def secondDim = data.take(16).asUInt

    def isAxpy = data.takeHigh(8).lsb

    def enResAdd = data.takeHigh(8)(1)

    def tag = data.takeHigh(6)
  }

  val io = new Bundle {
    val mulRes = slave(Stream(Bits(parallelBit bits)))
    val resAdd = slave(Stream(Bits(parallelBit bits)))
    //    val postScale = slave(Stream(Bits(serialBit bits)))
    val vecOut = master(Flow(util.AxiFrame(Bits(parallelBit bits), userBit = 6)))
    val scalarOut = master(Flow(Fragment(util.AxiFrame(Bits(serialBit bits), userBit = 6))))
    val cfg = slave(Stream(Config()))
    // Descriptor-scoped speculative controls. The cfg stream remains the
    // legacy 32-bit ABI; these controls select the wider internal dot bound.
    val speculativeMode = in Bool()
    val speculativeK = in UInt(3 bits)
    val postCfgTag = out Bits (6 bits)
  }

  val popVldNext = in Bool()
  val mulResFire = Bool().setAsReg().init(False)
  val mulResFireNext = Bool()

  noIoPrefix()
  util.AxiStreamSpecRenamer(io.mulRes)
  util.AxiStreamSpecRenamer(io.resAdd)
  //  util.AxiStreamSpecRenamer(io.postScale)
  util.AxiStreamSpecRenamer(io.vecOut)
  util.AxiStreamSpecRenamer(io.scalarOut)
  util.AxiStreamSpecRenamer(io.cfg)

  val tag = io.cfg.tag
  val tagReduceDly = Delay(tag, acc_latency + mul_latency, init = B(0, 6 bits))
  val tagAddDly = Delay(tag, add_latency, init = B(0, 6 bits))

  val ram = if (!inLineRam) new Bundle {
    val rdPort = master(util.MemRdPort(Bits(parallelBit bits), maxFirstDim))
    val wrPort = master(Flow(util.MemWrPort(Bits(parallelBit bits), maxFirstDim)))
  } else null

  val dotCond = ~io.cfg.isAxpy
  val fAxpyCond = io.cfg.isAxpy & io.cfg.firstDim =/= 0
  val pAxpyCond = io.cfg.isAxpy & io.cfg.firstDim === 0
  val ret = StreamDemuxOh(io.cfg, Seq(dotCond, fAxpyCond, pAxpyCond))

  val fifoCtrl = new util.StreamFifoCtrl(Bits(parallelBit bit), maxFirstDim, forFMax = true)

  if (!inLineRam) {
    fifoCtrl.rdPort <> ram.rdPort
    fifoCtrl.wrPort <> ram.wrPort
  }
  else {
    val mem = Mem(Bits(parallelBit bits), maxFirstDim)
    mem.addAttribute("ram_style", "distributed")
    mem.write(
      enable = fifoCtrl.wrPort.valid,
      address = fifoCtrl.wrPort.payload.address,
      data = fifoCtrl.wrPort.payload.data
    )
    fifoCtrl.rdPort.rsp := mem.readSync(
      enable = fifoCtrl.rdPort.cmd.valid,
      address = fifoCtrl.rdPort.cmd.payload
    )
  }

  val addLogic = new Area {
    val aSel = UInt(2 bits)
    val bSel = UInt(2 bits)

    val mul = Vec(Bits(width bits), bankLen)
    val psum = Vec(Bits(width bits), bankLen)
    val psumDly = Vec(Bits(width bits), bankLen)
    val res = Vec(Bits(width bits), bankLen)
    val zero = Bits(width bits)
    zero.clearAll()

    (mul, io.mulRes.payload.subdivideIn(bankLen slices)).zipped.foreach(_ := _)
    (res, io.resAdd.payload.subdivideIn(bankLen slices)).zipped.foreach(_ := _)

    val a = Vec(Flow(Bits(width bits)), bankLen)
    val b = Vec(Flow(Bits(width bits)), bankLen)
    val c = Vec(Flow(Bits(width bits)), bankLen)

    a.foreach(_.valid.set())
    b.foreach(_.valid.set())

    (c, a, b).zipped.foreach((c, a, b) => c := add_func(a, b))

    for (i <- 0 until bankLen / 2 - 1) {
      a(i).payload := Vec(mul(i), c(2 * i + 1).payload, psumDly(i), zero)(aSel)
      b(i).payload := Vec(psum(i), c(2 * i + 2).payload, res(i), zero)(bSel)
    }

    val offset = bankLen / 2 - 1
    for (i <- 0 until bankLen / 2) {
      a(i + offset).payload := Vec(mul(i + offset), mul(i * 2 + 0), psumDly(i + offset), zero)(aSel)
      b(i + offset).payload := Vec(psum(i + offset), mul(i * 2 + 1), res(i + offset), zero)(bSel)
    }

    a.last.payload := Vec(mul.last, zero, psumDly.last, zero)(aSel)
    b.last.payload := Vec(psum.last, zero, res.last, zero)(bSel)

    val vecOut = Vec(Bits(width bits), bankLen)
    val scalarOut = Bits(serialBit bits)

    (vecOut, c).zipped.foreach(_ := _.payload)
    scalarOut := c.head.payload

    val popDly = RegNextWhen(fifoCtrl.io.pop.payload, fifoCtrl.io.pop.valid)
    (psumDly, popDly.subdivideIn(bankLen slices)).zipped.foreach(_ := _)
    (psum, fifoCtrl.io.pop.payload.subdivideIn(bankLen slices)).zipped.foreach(_ := _)
    fifoCtrl.io.push.payload := vecOut.asBits

    io.vecOut.tdata := vecOut.asBits
  }

  def CascadeCnt(boundNext: (UInt, UInt), enInc: Bool) = {
    val cntLv0 = UInt(boundNext._1.getWidth bits).setAsReg().init(0)
    val cntLv1 = UInt(boundNext._2.getWidth bits).setAsReg().init(0)

    val cntLv0Ovf = Bool().setAsReg().init(False)
    val cntLv1Ovf = Bool().setAsReg().init(False)
    val cntOvf = Bool().setAsReg().init(False)

    val cntLv0Next = UInt(cntLv0.getWidth bits)
    val cntLv1Next = UInt(cntLv1.getWidth bits)

    val cntLv0OvfNext = cntLv0Next === boundNext._1
    val cntLv1OvfNext = cntLv1Next === boundNext._2
    val cntOvfNext = cntLv0OvfNext & cntLv1OvfNext

    cntLv0 := cntLv0Next
    cntLv1 := cntLv1Next
    cntLv0Ovf := cntLv0OvfNext
    cntLv1Ovf := cntLv1OvfNext
    cntOvf := cntOvfNext

    cntLv0Next := cntLv0
    cntLv1Next := cntLv1
    when(enInc) {
      cntLv0Next := cntLv0 + 1
      when(cntLv0Ovf) {
        cntLv0Next.clearAll()
        cntLv1Next := cntLv1 + 1
        when(cntLv1Ovf) {
          cntLv1Next.clearAll()
        }
      }
    }
    (List(cntLv0, cntLv1), List(cntLv0Ovf, cntLv1Ovf), cntOvf)
  }

  val dotCtrl = new Area {
    val cfg = ret(0)

    val cfgPayload = Config().setAsReg()
    val cfgPayloadNext = cfg.payload
    cfgPayload := cfgPayloadNext

    val cfgVld = Bool().setAsReg().init(False)
    val cfgVldNext = cfg.valid
    when(cfg.ready)(cfgVldNext.clear())
    cfgVld.addAttribute("max_fanout", 100)
    cfgVld := cfgVldNext

    val aSelNext = U(1, 2 bits)
    val bSelNext = U(1, 2 bits)

    val enMulResCntNext = mulResFireNext & cfgVldNext
    val enMulResCnt = Bool().setAsReg().init(False)
    enMulResCnt := enMulResCntNext

    val kSafe = UInt(3 bits)
    kSafe := io.speculativeK
    when(io.speculativeK === 0)(kSafe := 1)
    val secondDimCount = cfgPayloadNext.secondDim.resize(18) + U(1, 18 bits)
    val speculativeSecondDimBound =
      (secondDimCount * kSafe.resize(18) - U(1, 21 bits)).resize(18)
    val secondDimBound = UInt(18 bits)
    secondDimBound := Mux(io.speculativeMode, speculativeSecondDimBound,
      cfgPayloadNext.secondDim.resize(18))

    val (muResCnt, mulResCntOvf, mulResCntOvfReduce) = CascadeCnt(
      (cfgPayloadNext.firstDim, secondDimBound), enMulResCnt
    )

    val enAddOutCnt = Delay(enMulResCnt, reduceLatency, init = False)
    val addOutCntOvf = mulResCntOvf.map(ovf => Delay(ovf, reduceLatency, init = False))

    val flagClrCond = enMulResCnt & mulResCntOvfReduce
    val flagSetCond = RegNext(enAddOutCnt & addOutCntOvf.reduce(_ & _), init = False)
    val flagInv = Bool().setAsReg().init(True)
    val flagInvNext = Bool()
    flagInvNext := flagInv
    flagInv := flagInvNext
    when(flagClrCond)(flagInvNext.clear())
    when(flagSetCond)(flagInvNext.set())

    val accCnt = UInt(log2Up(maxFirstDim) bits).setAsReg().init(0)
    val accCntOvf = accCnt === cfgPayload.firstDim
    val accCntOvfDly = Delay(accCntOvf, reduceLatency + mul_latency, init = False)
    when(enMulResCnt) {
      accCnt := accCnt + 1
      when(accCntOvf) {
        accCnt.clearAll()
      }
    }

    //    val scaleFlow = Flow(Bits(width bits))
    //    val reduceFlow = Flow(Bits(width bits))
    //    val resFlow = mul_func(scaleFlow, reduceFlow)
    //    reduceFlow.valid := enAddOutCnt
    //    reduceFlow.payload := addLogic.scalarOut
    //    scaleFlow.valid := reduceFlow.valid
    //    scaleFlow.payload := io.postScale.payload
    //    io.postScale.ready := reduceFlow.valid
    //
    //    val accIn = Flow(Fragment(Bits(width bits)))
    //    val accOut = acc_func(accIn)
    //    accIn.valid := resFlow.valid
    //    accIn.fragment := resFlow.payload
    //    accIn.last := accCntOvfDly
    //
    //    io.scalarOut.valid := accOut.valid & accOut.last
    //    io.scalarOut.tdata := accOut.fragment
    //    io.scalarOut.tuser := tagReduceDly

    io.scalarOut.valid := enAddOutCnt
    io.scalarOut.tdata := addLogic.scalarOut
    io.scalarOut.tuser := tag
    io.scalarOut.last := Delay(accCntOvf, reduceLatency, init = False)

    cfg.ready := enAddOutCnt & addOutCntOvf.reduce(_ & _)
  }

  io.postCfgTag := dotCtrl.cfgPayload.asBits.takeHigh(6)

  val fAxpyCtrl = new Area {
    val cfg = ret(1)

    val cfgPayload = Config().setAsReg()
    val cfgPayloadNext = cfg.payload
    cfgPayload := cfgPayloadNext

    val cfgVld = Bool().setAsReg().init(False)
    val cfgVldNext = cfg.valid
    when(cfg.ready)(cfgVldNext.clear())
    cfgVld.addAttribute("max_fanout", 100)
    cfgVld := cfgVldNext

    val psumPopEn = Bool()
    val psumPushEn = Bool()

    val enMulResCntNext = mulResFireNext & cfgVldNext
    val enMulResCnt = Bool().setAsReg().init(False)
    enMulResCnt := enMulResCntNext

    val (muResCnt, mulResCntOvf, mulResCntOvfReduce) = CascadeCnt(
      (cfgPayloadNext.firstDim, cfgPayloadNext.secondDim), enMulResCnt
    )

    val mulResCntLastZero = Bool().setAsReg().init(True)
    val mulResCntLastZeroNext = Bool()
    mulResCntLastZero := mulResCntLastZeroNext
    mulResCntLastZeroNext := mulResCntLastZero

    val mulResCntLastNotZero = Bool().setAsReg().init(False)
    val mulResCntLastNotZeroNext = Bool()
    mulResCntLastNotZero := mulResCntLastNotZeroNext
    mulResCntLastNotZeroNext := mulResCntLastNotZero

    when(enMulResCnt) {
      when(mulResCntOvf.head) {
        mulResCntLastZeroNext.clear()
        mulResCntLastNotZeroNext.set()
        when(mulResCntOvf.last) {
          mulResCntLastZeroNext.set()
          mulResCntLastNotZeroNext.clear()
        }
      }
    }

    val enAddOutCnt = Delay(enMulResCnt, add_latency, init = False)
    val addOutCntOvf = mulResCntOvf.map(ovf => Delay(ovf, add_latency, init = False))

    //    io.resAdd.ready.clear()
    //    psumPopEn.clear()

    val psumPushEnReg = Delay(enMulResCnt & ~mulResCntOvf.last, add_latency, init = False)
    psumPushEn := psumPushEnReg

    //    bSel := 3
    //    when(enMulResCnt & mulResCntLastZero & cfgPayload.enResAdd) {
    //      io.resAdd.ready.set()
    //      bSel := 2
    //    }
    //    when(enMulResCnt & mulResCntLastNotZero) {
    //      psumPopEn.set()
    //      bSel := 0
    //    }

    val resAddReady = Bool().setAsReg().init(False)
    val resAddReadyNext = enMulResCntNext & mulResCntLastZeroNext & cfgPayloadNext.enResAdd
    resAddReady := resAddReadyNext
    io.resAdd.ready := resAddReady

    val psumPopEnReg = Bool().setAsReg().init(False)
    val psumPopEnRegNext = enMulResCntNext & mulResCntLastNotZeroNext
    psumPopEnReg := psumPopEnRegNext
    psumPopEn := psumPopEnReg

    val aSelNext = U(0, 2 bits)
    val bSelNext = UInt(2 bits)
    bSelNext := 3
    when(resAddReadyNext)(bSelNext := 2)
    when(psumPopEnRegNext)(bSelNext := 0)

    cfg.ready := enMulResCnt & mulResCntOvfReduce
    val vecOutVld = enAddOutCnt & addOutCntOvf.last
  }

  val pAxpyCtrl = new Area {
    val cfg = ret(2)

    val cfgPayload = Config().setAsReg()
    val cfgPayloadNext = cfg.payload
    cfgPayload := cfgPayloadNext

    val cfgVld = Bool().setAsReg().init(False)
    val cfgVldNext = cfg.valid
    when(cfg.ready)(cfgVldNext.clear())
    cfgVld.addAttribute("max_fanout", 100)
    cfgVld := cfgVldNext

    val cycleReduce = Bool().setAsReg().init(False)
    val cycleReduceNext = Bool()
    cycleReduce := cycleReduceNext
    cycleReduceNext := cycleReduce
    val cycleReduceDly = Bool().setAsReg().init(False)

    val enMulResCntNext = mulResFireNext & cfgVldNext
    val enMulResCnt = Bool().setAsReg().init(False)
    enMulResCnt := enMulResCntNext

    val mulResCnt = UInt(16 bits).setAsReg().init(0)
    val mulResCntNext = UInt(16 bits)
    val mulResCntLow = mulResCnt.take(log2Up(minFirstDim)).asUInt
    val mulResCntHigh = mulResCnt.drop(log2Up(minFirstDim)).asUInt

    val mulResCntOvf = Bool().setAsReg().init(False)
    val mulResCntOvfNext = mulResCntNext === cfgPayloadNext.secondDim
    mulResCntOvf := mulResCntOvfNext

    val mulResCntHighZero = Bool().setAsReg().init(True)
    val mulResCntHighZeroNext = Bool()
    mulResCntHighZero := mulResCntHighZeroNext
    mulResCntHighZeroNext := mulResCntHighZero

    val mulResCntHighNotZero = Bool().setAsReg().init(False)
    val mulResCntHighNotZeroNext = Bool()
    mulResCntHighNotZero := mulResCntHighNotZeroNext
    mulResCntHighNotZeroNext := mulResCntHighNotZero

    when(enMulResCnt) {
      when(mulResCntLow.andR) {
        mulResCntHighZeroNext.clear()
        mulResCntHighNotZeroNext.set()
      }
    }

    mulResCnt := mulResCntNext
    mulResCntNext := mulResCnt
    when(enMulResCnt) {
      mulResCntNext := mulResCnt + 1
      when(mulResCntOvf) {
        mulResCntNext.clearAll()
        mulResCntHighZeroNext.set()
        mulResCntHighNotZeroNext.clear()
        cycleReduceNext.set()
      }
    }

    val enMulResCntDly = Delay(enMulResCnt, add_latency, init = False)
    val enAddOutCnt = enMulResCntDly & ~cycleReduceDly
    val addOutCntOvf = Delay(mulResCntOvf, add_latency, init = False)
    when(enAddOutCnt & addOutCntOvf) {
      cycleReduceDly.set()
    }

    val reduceVldDly = Bool()
    val enCycleCnt = cycleReduceDly & reduceVldDly
    val cycleCnt = UInt(log2Up(minFirstDim) bits).setAsReg().init(0)
    val cycleCntNext = UInt(log2Up(minFirstDim) bits)

    val lastElem = Bool().setAsReg().init(False)
    lastElem.addAttribute("max_fanout", 100)
    val lastElemNext = cycleCntNext === Min(U(minFirstDim - 2), cfgPayloadNext.secondDim - 1)
    lastElem := lastElemNext

    cycleCnt := cycleCntNext
    cycleCntNext := cycleCnt
    when(enCycleCnt) {
      cycleCntNext := cycleCnt + 1
      when(lastElem) {
        cycleCntNext.clearAll()
        cycleReduceNext.clear()
        cycleReduceDly.clear()
      }
    }

    val psumPushEn = Bool()
    psumPushEn := Mux(cycleReduceDly, enCycleCnt & ~(enCycleCnt & lastElem), enMulResCntDly)

    val psumPopEn = Bool().setAsReg().init(False)
    val psumPopEnNext = Bool()
    psumPopEn := psumPopEnNext
    psumPopEnNext.clear()
    when(enMulResCntNext & mulResCntHighNotZeroNext)(psumPopEnNext.set())
    when(cycleReduceNext)(psumPopEnNext.set())

    val aSelNext = (cycleReduceNext ## B"0").asUInt
    val bSelNext = UInt(2 bits)
    val bSelBit = Bool().setAsReg().init(False)
    val bSelBitNext = enMulResCntNext & mulResCntHighZeroNext
    bSelBit := bSelBitNext
    bSelNext := (bSelBitNext ## bSelBitNext).asUInt

    val flagClrCond = enMulResCnt & mulResCntOvf
    val flagSetCond = RegNext(enCycleCnt & lastElem, init = False)
    val flagInv = Bool().setAsReg().init(True)
    val flagInvNext = Bool()
    flagInvNext := flagInv
    flagInv := flagInvNext
    when(flagClrCond)(flagInvNext.clear())
    when(flagSetCond)(flagInvNext.set())

    val vldToggle = Bool().setAsReg().init(False)
    val reduceVld = vldToggle & fifoCtrl.io.pop.fire
    vldToggle.toggleWhen(cycleReduce & fifoCtrl.io.pop.fire)

    val psumVld = Bool()
    psumVld := fifoCtrl.io.pop.fire
    when(cycleReduce) {
      psumVld := reduceVld
    }
    cfg.ready := enCycleCnt & lastElem

    reduceVldDly := Delay(psumVld & reduceVld, add_latency, init = False)
    val vecOutVld = cfgVld & enCycleCnt & lastElem
  }

  io.vecOut.valid := fAxpyCtrl.vecOutVld | pAxpyCtrl.vecOutVld
  io.vecOut.tuser := MuxOH(
    Vec(fAxpyCtrl.vecOutVld, pAxpyCtrl.vecOutVld),
    Vec(tagAddDly, tag)
  )

  val selVldNextOneHot = pAxpyCtrl.cfgVldNext ## fAxpyCtrl.cfgVldNext ## dotCtrl.cfgVldNext
  val selVldNextUInt = OHToUInt(selVldNextOneHot)
  val selVldUIntReg = UInt(2 bits).setAsReg().init(0)
  selVldUIntReg := selVldNextUInt

  val selFifoPushUInt = UInt(2 bits).setAsReg().init(0)
  val selFifoPopUInt = UInt(2 bits).setAsReg().init(0)
  selFifoPopUInt.addAttribute("keep", "true")
  selFifoPushUInt.addAttribute("keep", "true")
  selFifoPopUInt := selVldNextUInt
  selFifoPushUInt := selVldNextUInt

  fifoCtrl.io.push.valid := Vec(False, fAxpyCtrl.psumPushEn, pAxpyCtrl.psumPushEn)(selFifoPushUInt)
  fifoCtrl.io.pop.ready := Vec(False, fAxpyCtrl.psumPopEn, pAxpyCtrl.psumPopEn)(selFifoPopUInt)

  val aSel = UInt(2 bits).setAsReg().init(1)
  val bSel = UInt(2 bits).setAsReg().init(1)
  aSel.addAttribute("max_fanout", 100)
  bSel.addAttribute("max_fanout", 100)
  val aSelNext = Vec(dotCtrl.aSelNext, fAxpyCtrl.aSelNext, pAxpyCtrl.aSelNext)(selVldNextUInt)
  val bSelNext = Vec(dotCtrl.bSelNext, fAxpyCtrl.bSelNext, pAxpyCtrl.bSelNext)(selVldNextUInt)
  aSel := aSelNext
  bSel := bSelNext
  addLogic.aSel := aSel
  addLogic.bSel := bSel

  val ready = Bool().setAsReg().init(False)
  ready.addAttribute("max_fanout", 100)
  val readyNext = Bool()
  ready := readyNext
  readyNext := False
  when(dotCtrl.cfgVldNext)(readyNext := dotCtrl.flagInvNext)
  when(fAxpyCtrl.cfgVldNext)(readyNext := True)
  when(pAxpyCtrl.cfgVldNext)(readyNext := pAxpyCtrl.flagInvNext)

  io.mulRes.ready := ready

  mulResFireNext := popVldNext & readyNext
  mulResFire := mulResFireNext
}

object AddEngineNew extends App{
  SpinalVerilog(
    new AddEngineNew(
      width = 16,
      bankLen = 32,
      maxFirstDim = 32,
      mul_latency = util.fp16mul6.latency,
      add_latency =  util.fp16add6.latency,
      acc_latency = util.fp16acc16.latency,
      inLineRam = true,
      mul_func = util.fp16mul6.mul,
      add_func = util.fp16add6.add,
      acc_func = util.fp16acc16.acc
    )
  )
}
