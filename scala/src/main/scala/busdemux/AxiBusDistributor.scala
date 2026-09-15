package busdemux

import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axis._
import util.{LargeBankFifo, StreamAxiFrameFifo}

import scala.language.postfixOps

class AxiBusDistributor(
                         busWidth: Int,
                         dim: Int,
                         numOfCore: Int,
                         scaleBanks: Int,
                         maxToken: Int,
                         lnScaleBusTag: List[Int],
                         denseBusTag: List[Int],
                         kvCacheBusTag: List[Int],
                         sparseDotTagMap: List[(Int, Int, Int)],
                         sparseAxpyTagMap: List[(Int, Int, Int)],
                         denseCfgTag: List[Int],
                         sparseCfgTag: List[Int],
                         lnSqrCfgTag: List[Int],
                         kvCfgTag: (Int, Int),
                         mlpGTag: (Int, Int),
                         fp16ToFp32: Stream[Bits] => Stream[Bits] = x => x
                       ) extends Component {

  val cfg = Axi4StreamConfig(
    dataWidth = busWidth / 8,
    destWidth = 6,
    useDest = true,
    useLast = true,
    useKeep = true
  )

  val sparseBusTagMap = sparseDotTagMap ++ sparseAxpyTagMap

  val io = new Bundle {
    val bus = slave(Axi4Stream(cfg))
    val token = in UInt (log2Up(maxToken) bits)
    val preCfgTag = in Bits (6 bits)
    val postCfgTag = in Bits (6 bits)
    val enPredictor = in Bool()
    val kSzOut = slave(Stream(Bits(32 bits)))
    val vSzOut = slave(Stream(Bits(32 bits)))
    // P4 supplies the exact metadata slice for the currently streamed tile.
    // These ports bypass KvCacheCase's legacy token-zero/counting heuristic;
    // selection is enabled only while the production P4 frontend owns KV.
    val p4Enable = in Bool()
    val p4KSzOut = slave(Stream(Bits(32 bits)))
    val p4VSzOut = slave(Stream(Bits(32 bits)))

    //    val busDeMuxSel = in UInt (3 bits)
    //    val busMuxSel = in UInt (2 bits)
    //    val miscInMuxSel = in UInt (2 bits)
  }

  val int = new Bundle {
    val bus = master(Stream(Fragment(util.AxiFrame(Bits(busWidth bits), userBit = 6))))
    val lnScale = master(Stream(Bits(16 bits)))
    val zeroInt4 = master(Stream(Bits(8 bits)))
    val zeroInt8 = master(Stream(Bits(8 bits)))
    val preScale = master(Stream(Bits(16 bits)))
    val postScale = master(Stream(Bits(32 bits)))
  }

  //  val busFire = io.bus.fire
  //  busFire.addAttribute("mark_debug", "true")

  //  val startInfer = Bool().setAsReg().init(False)
  //  startInfer.setWhen(busFire)
  //  val haltDetected = Bool().setAsReg().init(False)
  //  val haltCnt = UInt(16 bits).setAsReg().init(0)
  //  val haltCntEn = Bool()
  //  val haltCntClr = Bool()
  //  when(haltCntEn) {
  //    haltCnt := haltCnt + 1
  //    when(haltCnt >= 6500) {
  //      haltDetected.set()
  //    }
  //  }
  //  when(haltCntClr) {
  //    haltCnt.clearAll()
  //  }
  //  haltCntEn := startInfer & ~busFire
  //  haltCntClr := busFire
  //  haltDetected.addAttribute("mark_debug", "true")
  //  io.bus.valid.addAttribute("mark_debug", "true")
  //  io.bus.ready.addAttribute("mark_debug", "true")
  //  io.bus.last.addAttribute("mark_debug", "true")

  val busDeMux = new StreamDemux(NoData(), 5)
  val busMux = new StreamMux(NoData(), 4)
  val miscInMux = new StreamMux(NoData(), 3)
  busDeMux.io.input.arbitrationFrom(io.bus)

  val busTag = UInt(6 bits)
  busTag := io.bus.dest
  //  busTag.addAttribute("mark_debug", "true")
  val mlpGBusHitNotSparse = busTag === mlpGTag._1 & ~io.enPredictor
  val lnScaleHit = lnScaleBusTag.map(_ === busTag).reduce(_ || _)
  val denseHit = denseBusTag.map(_ === busTag).reduce(_ || _) || mlpGBusHitNotSparse
  val sparseHit = sparseBusTagMap.map(_._1 === busTag).reduce(_ || _) & ~mlpGBusHitNotSparse
  val kvHit = kvCacheBusTag.map(_ === busTag).reduce(_ || _)

  val busDeMuxSel = UInt(3 bits)
  busDeMuxSel := 0
  when(denseHit) {
    busDeMuxSel := 1
  }.elsewhen(sparseHit) {
    busDeMuxSel := 2
  }.elsewhen(kvHit) {
    busDeMuxSel := 3
  }.elsewhen(lnScaleHit) {
    busDeMuxSel := 4
  }

  val busMuxSel = UInt(2 bits)
  busMuxSel := 0
  when(denseHit) {
    busMuxSel := 1
  }.elsewhen(sparseHit) {
    busMuxSel := 2
  }.elsewhen(kvHit) {
    busMuxSel := 3
  }

  val miscInMuxSel = UInt(2 bits)
  miscInMuxSel := 0
  when(sparseHit) {
    miscInMuxSel := 1
  }.elsewhen(kvHit) {
    miscInMuxSel := 2
  }

  val busDataFifo = new LargeBankFifo(Bits(busWidth bits), depth = 32, forFMax = true, split = 4, shallow = true)
  val busLinkFifo = new StreamFifo(Fragment(Bits(6 bits)), 32, forFMax = true)
  busDataFifo.io.push.arbitrationFrom(busMux.io.output)
  busDataFifo.io.push.payload := io.bus.data
  busLinkFifo.io.push.valid := busDataFifo.io.push.fire
  busLinkFifo.io.push.last := io.bus.last
  busLinkFifo.io.push.fragment := io.bus.dest.asBits
  busLinkFifo.io.pop.ready := busDataFifo.io.pop.fire
  int.bus.arbitrationFrom(busDataFifo.io.pop)
  int.bus.last := busLinkFifo.io.pop.last
  int.bus.tuser := busLinkFifo.io.pop.fragment
  int.bus.tdata := busDataFifo.io.pop.payload

  //  val busFifo = new StreamFifo(int.bus.payload, depth = 32, forFMax = true)
  //  busFifo.io.push.arbitrationFrom(busMux.io.output)
  //  busFifo.io.push.tdata := io.bus.data
  //  busFifo.io.push.last := io.bus.last
  //  busFifo.io.push.tuser := io.bus.dest.asBits
  //  busFifo.io.pop >> int.bus

  val miscFifo = new LargeBankFifo(Bits(busWidth bits), depth = 64, forFMax = true, split = 4, shallow = true)
  //  val miscFifo = new StreamFifo(Bits(busWidth bits), depth = 32, forFMax = true)
  miscFifo.io.push.arbitrationFrom(miscInMux.io.output)
  miscFifo.io.push.payload := io.bus.data

  busDeMux.io.select := busDeMuxSel
  busMux.io.select := busMuxSel
  miscInMux.io.select := miscInMuxSel

  val isVOut = io.bus.dest === kvCacheBusTag(1)
  val axpyHit = sparseAxpyTagMap.map(_._1 === io.bus.dest).reduce(_ || _)

  val tagFifo = new StreamFifo(Bits(10 bits), depth = 64, forFMax = true)
  tagFifo.io.push.valid := miscFifo.io.push.fire
  tagFifo.io.push.payload := axpyHit ## isVOut ## miscInMux.io.select ## io.bus.dest

  val tagFifoDest = Bits(6 bits)
  tagFifoDest := tagFifo.io.pop.payload.take(6)
  val tagFifoSel = Bits(2 bits)
  tagFifoSel := tagFifo.io.pop.payload.drop(6).take(2)

  //  tagFifoDest.addAttribute("mark_debug", "true")
  //  tagFifo.io.pop.valid.addAttribute("mark_debug", "true")
  //  tagFifo.io.pop.ready.addAttribute("mark_debug", "true")

  val miscOutDeMux = new StreamDemux(NoData(), 3)
  miscOutDeMux.io.select := tagFifo.io.pop.payload.drop(6).take(2).asUInt
  miscOutDeMux.io.input.arbitrationFrom(tagFifo.io.pop)
  miscFifo.io.pop.ready := tagFifo.io.pop.fire

  val ln = new Area {
    //    val buf = new StreamFifo(Bits(busWidth bits), depth = scaleBanks, forFMax = true)
    //    val scaleOut = Stream(Bits(16 bits))
    //    StreamWidthAdapter(buf.io.pop, scaleOut)
    //    buf.io.push.arbitrationFrom(busDeMux.io.outputs(4))
    //    buf.io.push.payload := io.bus.data
    //    int.lnScale << scaleOut

    val bufWidth = scala.math.max(16, 16 * dim / 512)
    val buf = new StreamFifo(Bits(bufWidth bits), 512, forFMax = true)
    val bufIn = Stream(Bits(busWidth bits))
    bufIn.arbitrationFrom(busDeMux.io.outputs(4))
    bufIn.payload := io.bus.data
    StreamWidthAdapter(bufIn, buf.io.push)
    val scaleOut = Stream(Bits(16 bits))
    StreamWidthAdapter(buf.io.pop, scaleOut)
    int.lnScale << scaleOut.queue(32, latency = 2, forFMax = true)
  }

  val dense = new Area {
    val gen = new DenseCase(busWidth)
    val bus = Stream(Bits(busWidth bits))
    bus.arbitrationFrom(miscOutDeMux.io.outputs(0))
    bus.payload := miscFifo.io.pop.payload

    val cnt = UInt(3 bits).setAsReg().init(0)
    val cntIsZero = Bool().setAsReg().init(True)
    val cntOvf = cnt === 4
    when(bus.fire) {
      cnt := cnt + 1
      cntIsZero.clear()
      when(cntOvf) {
        cnt := 0
        cntIsZero.set()
      }
    }

    val deMux = new StreamDemux(NoData(), 2)
    deMux.io.input.arbitrationFrom(bus)
    deMux.io.select := cntIsZero.asUInt

    val toScale = Stream(Bits(busWidth bits))
    toScale.arbitrationFrom(deMux.io.outputs(0))
    toScale.payload := bus.payload

    val toZero = Stream(Bits(busWidth bits))
    toZero.arbitrationFrom(deMux.io.outputs(1))
    toZero.payload := bus.payload

    val scaleFifo = new LargeBankFifo(Bits(busWidth bits), 8, forFMax = true, split = 4, shallow = true)
    scaleFifo.io.push << toScale
    val scale = Stream(Bits(16 bits))
    StreamWidthAdapter(scaleFifo.io.pop, scale)

    val zero = Stream(Bits(4 bits))
    val zeroPipe = zero.m2sPipe()
    val zeroAlign = Stream(Bits(8 bits))
    StreamWidthAdapter(toZero.m2sPipe(), zero)
    zeroAlign.arbitrationFrom(zeroPipe)
    zeroAlign.payload := zeroPipe.payload.resized
  }

  val sparse = new Area {
    val scaleZeroPackWidth = 20
    val scaleZeroPerPack = busWidth / scaleZeroPackWidth
    val truncWidth = scaleZeroPerPack * scaleZeroPackWidth

    val gen = new SparseCase(sparseBusTagMap)
    gen.io.tag := io.bus.dest.asBits

    val bus = Stream(util.AxiFrame(Bits(truncWidth bits), userBit = 6))
    bus.arbitrationFrom(miscOutDeMux.io.outputs(1))
    bus.tdata := miscFifo.io.pop.payload.take(truncWidth)
    bus.tuser := tagFifo.io.pop.payload.take(6)

    val tagHit = sparseBusTagMap.map(_._1 === bus.tuser).asBits()
    val packVec = sparseBusTagMap.map(x => U(x._3, 8 bits))
    val packCnt = MuxOH(tagHit, packVec)

    val packOut = Stream(Bits(scaleZeroPackWidth bits))

    val cntLv1 = UInt(8 bits).setAsReg().init(0)
    val cntLv2 = UInt(8 bits).setAsReg().init(0)
    val cntLv1Ovf = cntLv1 === scaleZeroPerPack - 1
    val cntLv2Ovf = cntLv2 === packCnt - 1
    bus.ready.clear()
    when(packOut.fire) {
      cntLv1 := cntLv1 + 1
      cntLv2 := cntLv2 + 1
      when(cntLv1Ovf) {
        cntLv1.clearAll()
        bus.ready.set()
      }
      when(cntLv2Ovf) {
        cntLv1.clearAll()
        cntLv2.clearAll()
        bus.ready.set()
      }
    }
    cntLv1.addAttribute("max_fanout", 100)

    val busPayloadSubDiv = bus.tdata.subdivideIn(scaleZeroPackWidth bits)
    packOut.valid := bus.valid
    packOut.payload := busPayloadSubDiv(cntLv1.resize(log2Up(scaleZeroPerPack) bits))

    val preScaleFifo = StreamFifo(Bits(16 bits), 32, latency = 2, forFMax = true)
    val postScaleFifo = StreamFifo(Bits(16 bits), 32, latency = 2, forFMax = true)
    val zeroFifo = StreamFifo(Bits(4 bits), 64, latency = 1, forFMax = true)

    packOut.freeRun()
    preScaleFifo.io.push.payload := packOut.payload.drop(4)
    postScaleFifo.io.push.payload := packOut.payload.drop(4)
    zeroFifo.io.push.payload := packOut.payload.take(4)

    val selAxpy = tagFifo.io.pop.payload.msb
    preScaleFifo.io.push.valid := packOut.valid & selAxpy
    postScaleFifo.io.push.valid := packOut.valid & ~selAxpy
    zeroFifo.io.push.valid := packOut.valid

    val preScale = preScaleFifo.io.pop
    val postScale = postScaleFifo.io.pop
    val zeroQueue = Stream(Bits(8 bits))
    zeroQueue.arbitrationFrom(zeroFifo.io.pop)
    zeroQueue.payload := B"0000" ## zeroFifo.io.pop.payload

    //    val fork = new StreamFork(NoData(), 2, synchronous = true)
    //    fork.io.input.arbitrationFrom(packOut)
    //
    //    val scale = Stream(Bits(16 bits))
    //    scale.arbitrationFrom(fork.io.outputs(0))
    //    scale.payload := packOut.payload.drop(4)
    //
    //    //    val dotHit = sparseDotTagMap.map(_._1 === bus.tuser).reduce(_ || _)
    //    //    val axpyHit = sparseAxpyTagMap.map(_._1 === bus.tuser).reduce(_ || _)
    //    //    val scaleDeMux = StreamDemuxOh(scale, Seq(axpyHit, dotHit))
    //
    //    val scaleDeMux = new StreamDemux(Bits(16 bits), 2)
    //    scaleDeMux.io.input << scale
    //    val preScale = scaleDeMux.io.outputs(1)
    //    val postScale = scaleDeMux.io.outputs(0)
    //    val selAxpy = tagFifo.io.pop.payload.msb
    //    scaleDeMux.io.select := selAxpy.asUInt
    //
    //    val zeroAlign = Stream(Bits(8 bits))
    //    zeroAlign.arbitrationFrom(fork.io.outputs(1))
    //    zeroAlign.payload := B"0000" ## packOut.payload.take(4)
    //
    //    val zeroQueue = zeroAlign.queue(32, latency = 1, forFMax = true)
  }

  val kv = new Area {
    val scaleZeroPackWidth = 32
    val scaleZeroPerPack = busWidth / scaleZeroPackWidth

    val gen = new KvCacheCase(busWidth, maxToken)
    gen.io.token := io.token
    val bus = Stream(Bits(busWidth bits))
    bus.arbitrationFrom(miscOutDeMux.io.outputs(2))
    bus.payload := miscFifo.io.pop.payload

    val extPack = Stream(Bits(scaleZeroPackWidth bits))
    StreamWidthAdapter(bus, extPack)

    val kPack = Stream(Bits(scaleZeroPackWidth bits))
    val vPack = Stream(Bits(scaleZeroPackWidth bits))
    val isVOut = tagFifo.io.pop.payload.dropHigh(1).msb

    val kCnt = UInt(log2Up(maxToken) bits).setAsReg().init(0)
    val kCntOvf = kCnt === io.token - 1

    val vCnt = UInt(log2Up(maxToken) bits).setAsReg().init(0)
    val vCntOvf = vCnt === io.token - 1

    val tokenHigh = io.token.drop(log2Up(scaleZeroPerPack)).asUInt
    val kCntHigh = kCnt.drop(log2Up(scaleZeroPerPack)).asUInt
    val vCntHigh = vCnt.drop(log2Up(scaleZeroPerPack)).asUInt
    val kCntLow = kCnt.take(log2Up(scaleZeroPerPack)).asUInt
    val vCntLow = vCnt.take(log2Up(scaleZeroPerPack)).asUInt
    val tokenHighZeroDly = RegNext(tokenHigh === 0, init = True)

    val kCntHighMatch = Bool().setAsReg().init(False)
    when(kPack.fire) {
      kCnt := kCnt + 1
      when(kCntLow.andR & kCntHigh === tokenHigh - 1) {
        kCntHighMatch.set()
      }
      when(kCntOvf) {
        kCnt := 0
        kCntHighMatch.clear()
      }
    }

    val vCntHighMatch = Bool().setAsReg().init(False)
    when(vPack.fire) {
      vCnt := vCnt + 1
      when(vCntLow.andR & vCntHigh === tokenHigh - 1) {
        vCntHighMatch.set()
      }
      when(vCntOvf) {
        vCnt := 0
        vCntHighMatch.clear()
      }
    }

    //    val kSelLocal = tokenHighZeroDly || kCntHigh === tokenHigh
    //    val vSelLocal = tokenHighZeroDly || vCntHigh === tokenHigh
    val kSelLocal = tokenHighZeroDly || kCntHighMatch
    val vSelLocal = tokenHighZeroDly || vCntHighMatch

    //    val kLocalCnt = UInt(16 bits).setAsReg().init(0)
    //    val kLocalErrDetect = Bool().setAsReg().init(False)
    //    when(kSelLocal & io.token > 17){
    //      kLocalCnt := kLocalCnt + 1
    //      when(kLocalCnt > 1024){
    //        kLocalErrDetect.set()
    //      }
    //    }.otherwise{
    //      kLocalCnt.clearAll()
    //      kLocalErrDetect.clear()
    //    }
    //    kLocalCnt.addAttribute("mark_debug", "true")
    //    kLocalErrDetect.addAttribute("mark_debug", "true")

    val extPackDeMux = new StreamDemux(Bits(scaleZeroPackWidth bits), 2)
    extPackDeMux.io.input << extPack.m2sPipe()
    extPackDeMux.io.select := isVOut.asUInt

    val kMux = new StreamMux(Bits(scaleZeroPackWidth bits), 3)
    kMux.io.inputs(0) << extPackDeMux.io.outputs(0)
    kMux.io.inputs(1) << io.kSzOut
    kMux.io.inputs(2) << io.p4KSzOut
    kMux.io.select := Mux(io.p4Enable, U(2, 2 bits), kSelLocal.asUInt.resize(2))
    kMux.io.output >> kPack

    val vMux = new StreamMux(Bits(scaleZeroPackWidth bits), 3)
    vMux.io.inputs(0) << extPackDeMux.io.outputs(1)
    vMux.io.inputs(1) << io.vSzOut
    vMux.io.inputs(2) << io.p4VSzOut
    vMux.io.select := Mux(io.p4Enable, U(2, 2 bits), vSelLocal.asUInt.resize(2))
    vMux.io.output >> vPack

    val kFork = new StreamFork(NoData(), 2)
    kFork.io.input.arbitrationFrom(kPack)

    val kScale = Stream(Bits(16 bits))
    kScale.arbitrationFrom(kFork.io.outputs(0))
    kScale.payload := kPack.payload.take(16)
    val kZero = Stream(Bits(8 bits))
    kZero.arbitrationFrom(kFork.io.outputs(1))
    kZero.payload := kPack.payload.drop(16).take(8)

    val vFork = new StreamFork(NoData(), 2)
    vFork.io.input.arbitrationFrom(vPack)

    val vScalePreInsert = Stream(Bits(16 bits))
    vScalePreInsert.arbitrationFrom(vFork.io.outputs(0))
    vScalePreInsert.payload := vPack.payload.take(16)
    val vZero = Stream(Bits(8 bits))
    vZero.arbitrationFrom(vFork.io.outputs(1))
    vZero.payload := vPack.payload.drop(16).take(8)

    val vScale = Stream(Bits(16 bits))
    val vScaleOne = Stream(Bits(16 bits))
    val vScaleCnt = UInt(log2Up(maxToken) bits).setAsReg().init(0)
    val vScaleCntIsZero = Bool().setAsReg().init(True)
    when(vScale.fire) {
      vScaleCnt := vScaleCnt + 1
      vScaleCntIsZero.clear()
      when(vScaleCnt === io.token) {
        vScaleCnt := 0
        vScaleCntIsZero.set()
      }
    }

    vScaleOne.valid := vScalePreInsert.valid & vScaleCntIsZero
    vScaleOne.payload := 0x3c00

    val vScaleMux = new StreamMux(Bits(16 bits), 2)
    vScaleMux.io.inputs(0) << vScalePreInsert
    vScaleMux.io.inputs(1) << vScaleOne
    vScaleMux.io.select := vScaleCntIsZero.asUInt
    vScaleMux.io.output >> vScale

//    kScale.payload.removeDataAssignments()
//    kZero.payload.removeDataAssignments()
//    kScale.payload := 0x3c00
//    kZero.payload := 1
//    vScale.payload.removeDataAssignments()
//    vZero.payload.removeDataAssignments()
//    vScale.payload := 0x3c00
//    vZero.payload := 1
  }

  dense.gen.io.bus.arbitrationFrom(busDeMux.io.outputs(1))
  sparse.gen.io.bus.arbitrationFrom(busDeMux.io.outputs(2))
  kv.gen.io.bus.arbitrationFrom(busDeMux.io.outputs(3))

  busMux.io.inputs(0).arbitrationFrom(busDeMux.io.outputs(0))
  busMux.io.inputs(1).arbitrationFrom(dense.gen.io.main)
  busMux.io.inputs(2).arbitrationFrom(sparse.gen.io.main)
  busMux.io.inputs(3).arbitrationFrom(kv.gen.io.main)

  miscInMux.io.inputs(0).arbitrationFrom(dense.gen.io.misc)
  miscInMux.io.inputs(1).arbitrationFrom(sparse.gen.io.misc)
  miscInMux.io.inputs(2).arbitrationFrom(kv.gen.io.misc)

  val postMlpGBusHitNotSparse = int.bus.tuser === mlpGTag._1 & ~io.enPredictor
  val postSparseHit = sparseBusTagMap.map(_._1 === int.bus.tuser).reduce(_ || _) & ~postMlpGBusHitNotSparse
  val postKHit = int.bus.tuser === kvCacheBusTag.head
  val postVHit = int.bus.tuser === kvCacheBusTag.last

  val zeroInt4Mux = new StreamMux(Bits(8 bits), 2)
  zeroInt4Mux.io.inputs(0) << dense.zeroAlign
  zeroInt4Mux.io.inputs(1) << sparse.zeroQueue
  zeroInt4Mux.io.select := postSparseHit.asUInt
  int.zeroInt4 << zeroInt4Mux.io.output

  val vZeroFifo = new StreamFifo(Bits(8 bits), 512, forFMax = true)
  vZeroFifo.io.push << kv.vZero
  val vScaleFifo = new StreamFifo(Bits(16 bits), 512, forFMax = true)
  vScaleFifo.io.push << kv.vScale

  val zeroInt8Mux = new StreamMux(Bits(8 bits), 3)
  zeroInt8Mux.io.inputs(0).valid.clear()
  zeroInt8Mux.io.inputs(0).payload.clearAll()
  zeroInt8Mux.io.inputs(1) << kv.kZero
  zeroInt8Mux.io.inputs(2) << vZeroFifo.io.pop
  zeroInt8Mux.io.select := 0
  when(postKHit)(zeroInt8Mux.io.select := 1)
  when(postVHit)(zeroInt8Mux.io.select := 2)
  int.zeroInt8 << zeroInt8Mux.io.output

  //  val sqrFactor = Stream(Bits(16 bits))
  //  sqrFactor.payload := 0x3c00
  //
  //  val postScaleMux = new StreamMux(Bits(16 bits), 5)
  //  postScaleMux.io.inputs(0) << dense.scale.queue(512, forFMax = true)
  //  postScaleMux.io.inputs(1) << sparse.postScale.queue(512, forFMax = true)
  //  postScaleMux.io.inputs(2) << kv.kScale.queue(512, forFMax = true)
  //  postScaleMux.io.inputs(3) << sqrFactor
  //  postScaleMux.io.inputs(4).valid.clear()
  //  postScaleMux.io.inputs(4).payload.clearAll()
  //  postScaleMux.io.output >> int.postScale

  //  val sqrFactor = Stream(Bits(32 bits))
  //  sqrFactor.payload := 0x3f800000
  val denseScaleFifo = new StreamFifo(Bits(32 bits), 512, forFMax = true)
  val sparseScaleFifo = new StreamFifo(Bits(32 bits), 512, forFMax = true)
  val kScaleFifo = new StreamFifo(Bits(32 bits), 512, forFMax = true)
  denseScaleFifo.io.push << fp16ToFp32(dense.scale)
  sparseScaleFifo.io.push << fp16ToFp32(sparse.postScale)
  kScaleFifo.io.push << fp16ToFp32(kv.kScale)
  val denseScale = denseScaleFifo.io.pop
  val sparseScale = sparseScaleFifo.io.pop
  val kScale = kScaleFifo.io.pop
  val postScaleMux = new StreamMux(Bits(32 bits), 4)
  postScaleMux.io.inputs(0) << denseScale
  postScaleMux.io.inputs(1) << sparseScale
  postScaleMux.io.inputs(2) << kScale
  postScaleMux.io.inputs(3).valid.clear()
  postScaleMux.io.inputs(3).payload.clearAll()
  postScaleMux.io.output >> int.postScale

  val postMlpCfgHitNotSparse = io.postCfgTag === mlpGTag._2 & ~io.enPredictor
  val postCfgDenseHit = denseCfgTag.map(_ === io.postCfgTag).reduce(_ || _) || postMlpCfgHitNotSparse
  val postCfgSparseHit = sparseCfgTag.map(_ === io.postCfgTag).reduce(_ || _) & ~postMlpCfgHitNotSparse
  val postCfgKvHit = io.postCfgTag === kvCfgTag._1
  //  val postCfgLnHit = lnSqrCfgTag.map(_ === io.postCfgTag).reduce(_ || _)

  //  sqrFactor.valid.setAsReg().init(False)
  //  sqrFactor.valid.setWhen(postCfgLnHit.rise())
  //  val sqrCnt = UInt(log2Up(scaleBanks / 4) bits).setAsReg().init(0)
  //  when(sqrFactor.fire) {
  //    sqrCnt := sqrCnt + 1
  //    when(sqrCnt === scaleBanks / 4 - 1) {
  //      sqrCnt := 0
  //      sqrFactor.valid.clear()
  //    }
  //  }

  val postSel = UInt(2 bits)
  postSel := 3
  when(postCfgDenseHit)(postSel := 0)
  when(postCfgSparseHit)(postSel := 1)
  when(postCfgKvHit)(postSel := 2)
  //  when(postCfgLnHit)(postSel := 3)
  postScaleMux.io.select := postSel

  val sparsePreScaleFifo = new StreamFifo(Bits(16 bits), 512, forFMax = true)
  sparsePreScaleFifo.io.push << sparse.preScale
  //  sparsePreScaleFifo.io.availability.addAttribute("mark_debug", "true")

  val preScaleMux = new StreamMux(Bits(16 bits), 3)
  preScaleMux.io.inputs(0) << sparsePreScaleFifo.io.pop
  preScaleMux.io.inputs(1) << vScaleFifo.io.pop
  preScaleMux.io.inputs(2).valid.clear()
  preScaleMux.io.inputs(2).payload.clearAll()
  preScaleMux.io.output >> int.preScale

  val preCfgSparseHit = sparseCfgTag.map(_ === io.preCfgTag).reduce(_ || _)
  val preCfgKvHit = io.preCfgTag === kvCfgTag._2

  val preSel = UInt(2 bits)
  preSel := 2
  when(preCfgSparseHit)(preSel := 0)
  when(preCfgKvHit)(preSel := 1)
  preScaleMux.io.select := preSel

  //  val scaleCnt = UInt(16 bits).setAsReg().init(0)
  //  scaleCnt.addAttribute("mark_debug", "true")
  //  when(preScaleMux.io.output.fire & preSel === 0)(scaleCnt := scaleCnt + 1)
  //  when(preSel =/= 0)(scaleCnt.clearAll())
}
