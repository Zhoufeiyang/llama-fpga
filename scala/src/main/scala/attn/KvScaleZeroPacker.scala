package attn

import adapter.FlowMux
import spinal.core._
import spinal.lib._
import util.{BottleNeckFifo, LargeBankFifo}

import scala.language.postfixOps

class KvScaleZeroPacker(
                         busWidth: Int,
                         head: Int,
                         layer: Int
                       ) extends Component {

  val io = new Bundle {
    val qScale = slave(Flow(Bits(16 bits)))
    val qZero = slave(Flow(Bits(8 bits)))
    val qOut = slave(Flow(Fragment(Bits(8 bits))))
    val kvBus = master(Stream(Fragment(Bits(busWidth bits))))
    val kSzOut = master(Stream(Bits(32 bits)))
    val vSzOut = master(Stream(Bits(32 bits)))
    val nextLayer = in Bool()
    val tokenIndexFlow = slave(Flow(Bits(6 bits)))
    // Absolute physical KV slot selected by the speculative/legacy command
    // path. Its low bits address one entry in the retained 64-byte line.
    val tokenPosition = in UInt(16 bits)
  }

  val scaleIsV = Bool().setAsReg().init(False)
  val zeroIsV = Bool().setAsReg().init(False)
  scaleIsV.toggleWhen(io.qScale.valid)
  zeroIsV.toggleWhen(io.qZero.valid)

  val kScale = Stream(Bits(16 bits))
  val kScalePipe = kScale.m2sPipe()
  kScale.valid := io.qScale.valid & ~scaleIsV
  kScale.payload := util.Fp16ScaleUp(io.qScale.payload, 2)

  val kZero = Stream(Bits(8 bits))
  val kZeroPipe = kZero.m2sPipe()
  kZero.valid := io.qZero.valid & ~zeroIsV
  kZero.payload := io.qZero.payload

  val vScale = Stream(Bits(16 bits))
  val vScalePipe = vScale.m2sPipe()
  vScale.valid := io.qScale.valid & scaleIsV
  vScale.payload := util.Fp16ScaleUp(io.qScale.payload, 2)

  val vZero = Stream(Bits(8 bits))
  val vZeroPipe = vZero.m2sPipe()
  vZero.valid := io.qZero.valid & zeroIsV
  vZero.payload := io.qZero.payload

  val kJoinEvent = StreamJoin(kScalePipe.toEvent(), kZeroPipe.toEvent())
  val kSz = Stream(Bits(32 bits))
  kSz.arbitrationFrom(kJoinEvent)
  kSz.payload := B"00000000" ## kZeroPipe.payload ## kScalePipe.payload

  val vJoinEvent = StreamJoin(vScalePipe.toEvent(), vZeroPipe.toEvent())
  val vSz = Stream(Bits(32 bits))
  vSz.arbitrationFrom(vJoinEvent)
  vSz.payload := B"00000000" ## vZeroPipe.payload ## vScalePipe.payload

  val depth = head * layer
  val numOfToken = busWidth / 32

  val tokenIndexCnt = UInt(16 bits).setAsReg().init(0)
  val explicitTokenLow = io.tokenPosition.takeLow(log2Up(numOfToken)).asUInt
  val firstTokenIndex = explicitTokenLow === 0
  when(io.tokenIndexFlow.valid) {
    tokenIndexCnt := tokenIndexCnt + 1
  }

  val k = new Area {

    val prefillIn = Stream(Bool())
    prefillIn.valid := io.tokenIndexFlow.valid & ~firstTokenIndex
    prefillIn.payload := io.tokenIndexFlow.payload === 0
    val prefillInLock = prefillIn.queue(64, forFMax = true)
    prefillInLock.ready.clear()

    val data = Stream(Bits(32 bits))
    val dataFire = data.fire

    val fifo = new BottleNeckFifo(busWidth, depth)
    val fifoPop = fifo.io.pop.m2sPipe()

    val tokenCnt = UInt(log2Up(numOfToken) bits).setAsReg().init(0)
    val depthCnt = UInt(log2Up(depth) bits).setAsReg().init(0)
    val tokenCntAbout2Ovf = tokenCnt === numOfToken - 2
    val depthCntOvf = depthCnt === depth - 1
    val isTokenZero = Bool().setAsReg().init(True)
    val tokenCntOvfReg = Bool().setAsReg().init(False)
    when(io.tokenIndexFlow.valid) {
      tokenCnt := explicitTokenLow
      isTokenZero := explicitTokenLow === 0
      tokenCntOvfReg := explicitTokenLow.andR
    }
    isTokenZero.addAttribute("max_fanout", "100")
    when(dataFire) {
      depthCnt := depthCnt + 1
      when(depthCntOvf) {
        tokenCnt := tokenCnt + 1
        isTokenZero.clear()
        depthCnt.clearAll()
        when(tokenCntAbout2Ovf) {
          tokenCntOvfReg.set()
        }
        when(tokenCntOvfReg) {
          tokenCnt.clearAll()
          isTokenZero.set()
          tokenCntOvfReg.clear()
        }
      }
    }

    val pushVec = Vec(Bits(32 bits), numOfToken)
    pushVec.assignFromBits(fifoPop.payload)
    pushVec(tokenCnt) := data.payload

    val pushVecBits = pushVec.asBits
    fifo.io.push.valid := dataFire & ~tokenCntOvfReg
    fifo.io.push.payload := pushVecBits
    fifoPop.ready := dataFire & ~isTokenZero

    val toBus = Stream(Fragment(Bits(busWidth bits)))
    toBus.valid := dataFire & tokenCntOvfReg
    toBus.last.set()
    toBus.payload := pushVecBits

    val flag = Bool().setAsReg().init(True)
    flag.setWhen(fifoPop.fire)
    flag.addAttribute("max_fanout", "100")
    val vld = Bool().setAsReg().init(True)
    vld.setWhen(io.nextLayer)
    vld.addAttribute("max_fanout", "100")
    val sOut = Stream(Bits(32 bits))
    sOut.valid := fifoPop.valid & flag & ~isTokenZero & vld

    val outCnt = UInt(log2Up(numOfToken) bits).setAsReg().init(0)
    val outCntOvf = outCnt === tokenCnt - 1
    val headCnt = UInt(log2Up(head) bits).setAsReg().init(0)
    val layerCnt = UInt(log2Up(layer) bits).setAsReg().init(0)
    val lastLayer = layerCnt === layer - 1
    val sOutFire = sOut.fire
    when(sOutFire) {
      outCnt := outCnt + 1
      when(outCntOvf) {
        outCnt.clearAll()
        flag.clear()
        headCnt := headCnt + 1
        when(headCnt === head - 1) {
          headCnt.clearAll()
          vld.clear()
          layerCnt := layerCnt + 1
          when(lastLayer) {
            layerCnt.clearAll()
            prefillInLock.ready.set()
          }
        }
      }
    }

    data << kSz
    data.ready := ~sOut.valid

    val dataSplit = fifoPop.payload.subdivideIn(32 bits)
    sOut.payload := dataSplit(outCnt)

    val throwCond = prefillInLock.payload & lastLayer
    val sOutThrow = sOut.throwWhen(throwCond)
  }

  val v = new Area {

    val prefillIn = Stream(Bool())
    prefillIn.valid := io.tokenIndexFlow.valid & ~firstTokenIndex
    prefillIn.payload := io.tokenIndexFlow.payload === 0
    val prefillInLock = prefillIn.queue(64, forFMax = true)
    prefillInLock.ready.clear()

    val data = Stream(Bits(32 bits))
    val dataFire = data.fire

    val fifo = new BottleNeckFifo(busWidth, depth)
    val fifoPop = fifo.io.pop.m2sPipe()

    val tokenCnt = UInt(log2Up(numOfToken) bits).setAsReg().init(0)
    val depthCnt = UInt(log2Up(depth) bits).setAsReg().init(0)
    val tokenCntAbout2Ovf = tokenCnt === numOfToken - 2
    val depthCntOvf = depthCnt === depth - 1
    val isTokenZero = Bool().setAsReg().init(True)
    val tokenCntOvfReg = Bool().setAsReg().init(False)
    when(io.tokenIndexFlow.valid) {
      tokenCnt := explicitTokenLow
      isTokenZero := explicitTokenLow === 0
      tokenCntOvfReg := explicitTokenLow.andR
    }
    isTokenZero.addAttribute("max_fanout", "100")
    when(dataFire) {
      depthCnt := depthCnt + 1
      when(depthCntOvf) {
        tokenCnt := tokenCnt + 1
        isTokenZero.clear()
        depthCnt.clearAll()
        when(tokenCntAbout2Ovf) {
          tokenCntOvfReg.set()
        }
        when(tokenCntOvfReg) {
          tokenCnt.clearAll()
          isTokenZero.set()
          tokenCntOvfReg.clear()
        }
      }
    }

    val pushVec = Vec(Bits(32 bits), numOfToken)
    pushVec.assignFromBits(fifoPop.payload)
    pushVec(tokenCnt) := data.payload

    val pushVecBits = pushVec.asBits
    fifo.io.push.valid := dataFire & ~tokenCntOvfReg
    fifo.io.push.payload := pushVecBits
    fifoPop.ready := dataFire & ~isTokenZero

    val toBus = Stream(Fragment(Bits(busWidth bits)))
    toBus.valid := dataFire & tokenCntOvfReg
    toBus.last.set()
    toBus.payload := pushVecBits

    val flag = Bool().setAsReg().init(True)
    flag.setWhen(fifoPop.fire)
    flag.addAttribute("max_fanout", "100")
    val vld = Bool().setAsReg().init(True)
    vld.setWhen(io.nextLayer)
    vld.addAttribute("max_fanout", "100")
    val sOut = Stream(Bits(32 bits))
    sOut.valid := fifoPop.valid & flag & ~isTokenZero & vld

    val outCnt = UInt(log2Up(numOfToken) bits).setAsReg().init(0)
    val outCntOvf = outCnt === tokenCnt - 1
    val headCnt = UInt(log2Up(head) bits).setAsReg().init(0)
    val layerCnt = UInt(log2Up(layer) bits).setAsReg().init(0)
    val lastLayer = layerCnt === layer - 1
    val sOutFire = sOut.fire
    when(sOutFire) {
      outCnt := outCnt + 1
      when(outCntOvf) {
        outCnt.clearAll()
        flag.clear()
        headCnt := headCnt + 1
        when(headCnt === head - 1) {
          headCnt.clearAll()
          vld.clear()
          layerCnt := layerCnt + 1
          when(lastLayer) {
            layerCnt.clearAll()
            prefillInLock.ready.set()
          }
        }
      }
    }

    data << vSz
    data.ready := ~sOut.valid

    val dataSplit = fifoPop.payload.subdivideIn(32 bits)
    sOut.payload := dataSplit(outCnt)
    val throwCond = prefillInLock.payload & lastLayer
    val sOutThrow = sOut.throwWhen(throwCond)
  }

  val packLen = busWidth / 8
  val qOutAdapt = History(io.qOut.fragment, packLen, when = io.qOut.valid).reverse.asBits
  val qOutCnt = UInt(log2Up(packLen) bits).setAsReg().init(0)
  val qOutCntOvf = qOutCnt === packLen - 1
  val qOutBus = Stream(Fragment(Bits(busWidth bits)))
  qOutBus.valid := io.qOut.valid & qOutCntOvf
  qOutBus.last := io.qOut.last
  qOutBus.fragment := qOutAdapt
  when(io.qOut.valid) {
    qOutCnt := qOutCnt + 1
    when(qOutCntOvf) {
      qOutCnt.clearAll()
    }
  }

  io.kSzOut << k.sOutThrow.m2sPipe().m2sPipe()
  io.vSzOut << v.sOutThrow.m2sPipe().m2sPipe()

  val qOutBusPipe = qOutBus.m2sPipe().m2sPipe()
  val kToBusPipe = k.toBus.m2sPipe()
  val vToBusPipe = v.toBus.m2sPipe()

  val busFifo = new StreamFifo(Fragment(Bits(busWidth bits)), 32, forFMax = true)
  val busMux = new StreamMux(Fragment(Bits(busWidth bits)), 3)
  busMux.io.inputs(0) << kToBusPipe
  busMux.io.inputs(1) << vToBusPipe
  busMux.io.inputs(2) << qOutBusPipe
  busMux.io.output >> busFifo.io.push
  io.kvBus << busFifo.io.pop

  val enInc = Bool()
  val headCnt = UInt(log2Up(head * 2) bits).setAsReg().init(0)
  val layerCnt = UInt(log2Up(layer) bits).setAsReg().init(0)
  val tokenCnt = UInt(log2Up(numOfToken) bits).setAsReg().init(0)
  //  val szToMem = tokenCnt.andR

  val szToMem = Bool().setAsReg().init(False)
  val szToMemNext = Bool()
  szToMemNext := szToMem
  szToMem := szToMemNext

  when(enInc) {
    headCnt := headCnt + 1
    when(headCnt === head * 2 - 1) {
      headCnt.clearAll()
      layerCnt := layerCnt + 1
      when(layerCnt === layer - 1) {
        layerCnt.clearAll()
        tokenCnt := tokenCnt + 1
        when(tokenCnt === numOfToken - 2) {
          szToMemNext.set()
        }
        when(szToMem) {
          szToMemNext.clear()
        }
      }
    }
  }

  val enStateCntInc = Bool()
  val stateCnt = UInt(2 bits).setAsReg().init(0)
  val stateCntNext = UInt(2 bits)
  stateCntNext := stateCnt
  stateCnt := stateCntNext
  when(enStateCntInc) {
    stateCntNext := stateCnt + 1
  }

  enInc := qOutBusPipe.fire & qOutBusPipe.last
  enStateCntInc := szToMem & busMux.io.output.fire & busMux.io.output.last

  // Re-seek the retained metadata line on every launch. This is the logical
  // rollback mechanism: a rejected future slot is overwritten in place, while
  // an incomplete line remains buffered until its true line-end token arrives.
  when(io.tokenIndexFlow.valid) {
    tokenCnt := explicitTokenLow
    szToMemNext := explicitTokenLow.andR
    stateCntNext.clearAll()
  }

  //  val select = UInt(2 bits)
  //  select := 2
  //  when(szToMem & stateCnt === 0)(select := 0)
  //  when(szToMem & stateCnt === 2)(select := 1)

  val select = UInt(2 bits).setAsReg().init(2)
  val selectNext = UInt(2 bits)
  select := selectNext
  selectNext := 2
  select.addAttribute("max_fanout", 100)
  when(szToMemNext & stateCntNext === 0)(selectNext := 0)
  when(szToMemNext & stateCntNext === 2)(selectNext := 1)

  busMux.io.select := select
}

object KvScaleZeroPacker extends App {
  SpinalVerilog(new KvScaleZeroPacker(128, 32, 32))
}
