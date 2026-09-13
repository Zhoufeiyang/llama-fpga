package top

import spinal.core.{ResetKind, _}
import spinal.lib._
import spinal.lib.bus.amba4.axi.Axi4SpecRenamer
import spinal.lib.bus.amba4.axilite.{AxiLite4, AxiLite4SpecRenamer}

import scala.language.postfixOps

class DataPath_xN(
                   sync: Boolean,
                   numOfCore: Int,
                   DMA_SPLIT: List[Int],
                   baseAddr: List[Int],
                   cmdAddrWidth: List[Int],
                   splitBaseAddr: List[(BigInt, BigInt)],

                   busWidth: Int,
                   sgSplit: Int,

                   dim: Int,
                   head: Int,
                   headDim: Int,
                   predDim: Int,
                   mlpDim: Int,
                   maxToken: Int,
                   layer: Int,
                   ropePoint: Int,
                   sqrtHeadDim: Int,
                   vocabSize: Int,

                   ropeTagMap: List[(Int, Int)],
                   quantTagMap: List[(Int, Int)],
                   softmaxTag: (Int, Int, Int),
                   lnInTagMap: List[(Int, Int)],
                   zeroFilterTagMap: List[(Int, Int)],

                   logitsTag: (Int, Int),
                   resAddTag: Int,
                   vLocalTag: Int,
                   normFilterTag: Int,
                   resOut2NodeTag: Int,
                   p2sOut2NodeTag: List[Int],
                   index2NodeTag: Int,
                   index2UgTag: (Int, Int, Int),

                   attnLnTag: Int,
                   logitsLnTag: Int,
                   lmHeadParamTag: Int,
                   attnVParamTag: Int,
                   mlpDParamTag: Int,
                   kvInTag: List[Int],

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

                   qkMulTag: (Int, Int, Int),
                   ugMulTag: (Int, Int, Int),
                   axpyTensorInTag: (Int, Int, Int),
                   axpyParamInTag: List[Int],
                   tokenTag: (Int, Int, Int),
                   vTensorTag: (Int, Int),

                   zeroFilterTag2SeqLen: List[(Int, Int)],

                   vecIn2ResTag: List[Int],
                   vecIn2ScalarTag: List[Int],
                   serial2VecOutTag: List[Int],
                   busIn2VecOutTag: List[Int],
                   engine2VecOutTag: List[Int],
                   lnOut2VecTag: List[Int],
                   dotOut2VecTag: List[Int],
                   rope2VecTag: Int,
                   dotOut2NodeTag: List[Int],
                   cfgInsertTag: (Int, Int, Int, Int),

                   mul_func: (Flow[Bits], Flow[Bits]) => Flow[Bits],
                   add_func: (Flow[Bits], Flow[Bits]) => Flow[Bits],
                   sub_func: (Flow[Bits], Flow[Bits]) => Flow[Bits],
                   div_func: (Flow[Bits], Flow[Bits]) => Flow[Bits],
                   acc_func: Flow[Fragment[Bits]] => Flow[Fragment[Bits]],
                   act_func: Flow[Bits] => Flow[Bits],
                   mul_func_block: (Stream[Bits], Stream[Bits]) => Stream[Bits],

                   rope_highPcs_mul_func: (Flow[Bits], Flow[Bits]) => Flow[Bits],
                   rope_toInt_func: Flow[Bits] => Flow[Bits],
                   rope_fromInt_func: Flow[Bits] => Flow[Bits],

                   quant_conv_func: Flow[Bits] => Flow[Bits],
                   deQuant_int4_conv_func: Flow[Bits] => Flow[Bits],
                   deQuant_int8_conv_func: Flow[Bits] => Flow[Bits],

                   add_latency: Int,
                   mul_latency: Int,
                   acc_latency: Int,
                   exp_latency: Int,
                   div_latency: Int,
                   deQuant_latency: Int,

                   expo_func: Flow[Bits] => Flow[Bits],
                   rsqrt_func: Flow[Bits] => Flow[Bits],
                   lt_func: (Flow[Bits], Flow[Bits]) => Flow[Bool],

                   fp16toFp32_func: Flow[Bits] => Flow[Bits],
                   fp16toFp32_func_block: Stream[Bits] => Stream[Bits],
                   fp32toFp16_func: Flow[Bits] => Flow[Bits],
                   fp32mul_func: (Flow[Bits], Flow[Bits]) => Flow[Bits],
                   fp32mul_func_block: (Stream[Bits], Stream[Bits]) => Stream[Bits],
                   fp32acc_func: Flow[Fragment[Bits]] => Flow[Fragment[Bits]],
                   fp32rsqrt_func: Flow[Bits] => Flow[Bits],
                   fp32add_func: (Flow[Bits], Flow[Bits]) => Flow[Bits],
                   fp32exp_func: Flow[Bits] => Flow[Bits],
                   fp32div_func: (Flow[Bits], Flow[Bits]) => Flow[Bits],
                   fp32lt_func: (Flow[Bits], Flow[Bits]) => Flow[Bool],
                   fp32sub_func: (Flow[Bits], Flow[Bits]) => Flow[Bits],
                   mix_mul_func: (Flow[Bits], Flow[Bits]) => Flow[Bits],
                   add_conv_func: (Flow[Bits], Flow[Bits]) => Flow[Bits],

                   toFp32_latency: Int,
                   toFp16_latency: Int,
                   fp32Mul_latency: Int,
                   fp32Add_latency: Int,
                   fp32Acc_latency: Int,
                   fp32Exp_latency: Int,
                   fp32Div_latency: Int,

                   dotMaxFirstDim: Int,
                   axpyMaxFirstDim: Int,
                   wkvOutFifoDepth: Int,
                   vecP2sFifoDepth: Int,
                   vecOutFifoDepth: Int,
                   resetLowPolarity: Boolean = true
                 ) extends Component {

  val ctrl = slave(AxiLite4(32, 32))
  val cfg = new AxiLiteCtrl(resetLowPolarity)
  cfg.io.ctrl << ctrl
  ctrl.setName("S00_AXIL")
  AxiLite4SpecRenamer(ctrl)

  val softReset = out Bool()
  softReset := cfg.resetOut

  clockDomain.clock.setName("S00_ACLK")
  clockDomain.reset.setName("S00_ARESETN")

  val core_clk = if (!sync) in Vec(Bool(), numOfCore) else null
  val core_rstn = if (!sync) in Vec(Bool(), numOfCore) else null
  val internalClock = Range(0, numOfCore).map(i => ClockDomain.internal("clk_" + i.toString, config = ClockDomainConfig(resetActiveLevel = LOW)))
  for (i <- 0 until numOfCore) {
    if (sync) {
      internalClock(i).clock := clockDomain.readClockWire
      internalClock(i).reset := clockDomain.readResetWire
    } else {
      internalClock(i).clock := core_clk(i)
      internalClock(i).reset := core_rstn(i)
    }
  }

  val coreArea = for (i <- 0 until numOfCore) yield new ClockingArea(internalClock(i)) {
    val core = new DataPath(baseAddr(i), cmdAddrWidth(i), splitBaseAddr(i), i, numOfCore, busWidth, sgSplit, dim, head, headDim, predDim, mlpDim, maxToken, layer, ropePoint, sqrtHeadDim, vocabSize,
      ropeTagMap, quantTagMap, softmaxTag, lnInTagMap, zeroFilterTagMap,
      logitsTag, resAddTag, vLocalTag, normFilterTag, resOut2NodeTag, p2sOut2NodeTag, index2NodeTag, index2UgTag, attnLnTag, logitsLnTag, lmHeadParamTag, attnVParamTag, mlpDParamTag, kvInTag,
      lnScaleBusTag, denseBusTag, kvCacheBusTag, sparseDotTagMap, sparseAxpyTagMap, denseCfgTag, sparseCfgTag, lnSqrCfgTag, kvCfgTag, mlpGTag,
      qkMulTag, ugMulTag, axpyTensorInTag, axpyParamInTag, tokenTag, vTensorTag,
      zeroFilterTag2SeqLen, vecIn2ResTag, vecIn2ScalarTag, serial2VecOutTag, busIn2VecOutTag, engine2VecOutTag, lnOut2VecTag, dotOut2VecTag, rope2VecTag, dotOut2NodeTag, cfgInsertTag,
      mul_func, add_func, sub_func, div_func, acc_func, act_func, mul_func_block,
      rope_highPcs_mul_func, rope_toInt_func, rope_fromInt_func, quant_conv_func, deQuant_int4_conv_func, deQuant_int8_conv_func, add_latency, mul_latency, acc_latency, exp_latency, div_latency, deQuant_latency,
      expo_func, rsqrt_func, lt_func,
      fp16toFp32_func, fp16toFp32_func_block, fp32toFp16_func, fp32mul_func, fp32mul_func_block, fp32acc_func, fp32rsqrt_func, fp32add_func, fp32exp_func, fp32div_func, fp32lt_func, fp32sub_func, mix_mul_func, add_conv_func,
      toFp32_latency, toFp16_latency, fp32Mul_latency, fp32Add_latency, fp32Acc_latency, fp32Exp_latency, fp32Div_latency,
      dotMaxFirstDim, axpyMaxFirstDim, wkvOutFifoDepth, vecP2sFifoDepth, vecOutFifoDepth, DMA_SPLIT(i)
    )
  }

  // Broadcast one speculative sequence to every core.  StreamFork waits for
  // all branches, preserving token alignment even when one core is stalled;
  // each branch then crosses into its core clock domain when required.
  val speculativeTokenFork = new StreamFork(cfg.io.speculativeTokenIndex.payloadType, numOfCore)
  speculativeTokenFork.io.input << cfg.io.speculativeTokenIndex

  val m_axi = for (i <- 0 until numOfCore) yield {
    if (DMA_SPLIT(i) == 1) coreArea(i).core.m_axi.toIo() else null
  }

  for (i <- 0 until numOfCore) {
    if (DMA_SPLIT(i) == 1) m_axi(i).setName("M0" + i.toString + "_AXI")
    if (!sync) {
      core_clk(i).setName("M0" + i.toString + "_ACLK")
      core_rstn(i).setName("M0" + i.toString + "_ARESETN")
    }
  }

  val m_axi_hp = for (i <- 0 until numOfCore) yield {
    if (DMA_SPLIT(i) != 1) coreArea(i).core.m_axi_hp.map(_.toIo()) else null
  }

  for (i <- 0 until numOfCore) {
    coreArea(i).core.speculativeEnable := cfg.io.speculativeEnable
    coreArea(i).core.speculativeQuery := cfg.io.speculativeQuery
    coreArea(i).core.speculativeCommitted := cfg.io.speculativeCommitted.resized
    coreArea(i).core.perfWindowActive := cfg.io.perfWindowActive
    coreArea(i).core.perfWindowClear := cfg.io.perfWindowClear
    // The descriptor is broadcast to every command generator.  A launch is
    // accepted only when all cores are able to latch the same descriptor;
    // this keeps the per-core command/token state aligned.
    coreArea(i).core.speculativeBatch.valid := cfg.io.speculativeBatch.valid
    coreArea(i).core.speculativeBatch.payload := cfg.io.speculativeBatch.payload
    coreArea(i).core.speculativeBatch.valid.addTag(crossClockDomain)
    coreArea(i).core.speculativeBatch.ready.addTag(crossClockDomain)
    coreArea(i).core.speculativeBatch.payload.mode.addTag(crossClockDomain)
    coreArea(i).core.speculativeBatch.payload.k.addTag(crossClockDomain)
    coreArea(i).core.speculativeBatch.payload.projectionTag.addTag(crossClockDomain)
    coreArea(i).core.speculativeBatch.payload.layerId.addTag(crossClockDomain)
    coreArea(i).core.speculativeBatch.payload.rows.addTag(crossClockDomain)
    coreArea(i).core.speculativeBatch.payload.beatsPerRow.addTag(crossClockDomain)
    coreArea(i).core.toAxiLite.projectionDone.addTag(crossClockDomain)
    coreArea(i).core.toAxiLite.projectionError.addTag(crossClockDomain)
    coreArea(i).core.toAxiLite.perfWeightBytes.addTag(crossClockDomain)
    coreArea(i).core.toAxiLite.perfKvReadBytes.addTag(crossClockDomain)
    coreArea(i).core.toAxiLite.perfKvWriteBytes.addTag(crossClockDomain)
    coreArea(i).core.toAxiLite.perfVerifyCycles.addTag(crossClockDomain)
    coreArea(i).core.toAxiLite.perfMemoryStallCycles.addTag(crossClockDomain)
    coreArea(i).core.speculativeEnable.addTag(crossClockDomain)
    coreArea(i).core.speculativeQuery.addTag(crossClockDomain)
    coreArea(i).core.speculativeCommitted.addTag(crossClockDomain)
    coreArea(i).core.perfWindowActive.addTag(crossClockDomain)
    coreArea(i).core.perfWindowClear.addTag(crossClockDomain)
    if (sync) {
      val legacyToken = cfg.io.tokenIndex.m2sPipe.m2sPipe.toStream.queue(size = 2, forFMax = true)
      val speculativeToken = speculativeTokenFork.io.outputs(i)
      coreArea(i).core.tokenIndex.valid := speculativeToken.valid || legacyToken.valid
      coreArea(i).core.tokenIndex.tdata := Mux(speculativeToken.valid, speculativeToken.tdata, legacyToken.tdata)
      coreArea(i).core.tokenIndex.tuser := Mux(speculativeToken.valid, speculativeToken.tuser, legacyToken.tuser)
      speculativeToken.ready := coreArea(i).core.tokenIndex.ready && speculativeToken.valid
      legacyToken.ready := coreArea(i).core.tokenIndex.ready && !speculativeToken.valid
      coreArea(i).core.tokenIndex.addTag(crossClockDomain)
    }
    else {
      val legacyToken = cfg.io.tokenIndex.toStream.queue(size = 32, pushClock = clockDomain, popClock = coreArea(i).clockDomain)
      val speculativeToken = speculativeTokenFork.io.outputs(i).queue(size = 8, pushClock = clockDomain, popClock = coreArea(i).clockDomain)
      coreArea(i).core.tokenIndex.valid := speculativeToken.valid || legacyToken.valid
      coreArea(i).core.tokenIndex.tdata := Mux(speculativeToken.valid, speculativeToken.tdata, legacyToken.tdata)
      coreArea(i).core.tokenIndex.tuser := Mux(speculativeToken.valid, speculativeToken.tuser, legacyToken.tuser)
      speculativeToken.ready := coreArea(i).core.tokenIndex.ready && speculativeToken.valid
      legacyToken.ready := coreArea(i).core.tokenIndex.ready && !speculativeToken.valid
    }

    if (sync)
      coreArea(i).core.aresetn := clockDomain.readResetWire
    else
      coreArea(i).core.aresetn := core_rstn(i)
  }

  if (numOfCore == 1 && DMA_SPLIT.head == 1 || numOfCore == 4) {
    for (i <- 0 until numOfCore) {
      coreArea(i).core.cmdSel := Delay(cfg.io.cmdSel, 2, init = U(0, 2 bits))
    }
  }

  cfg.io.speculativeBatch.ready := coreArea.map(_.core.speculativeBatch.ready).reduce(_ && _)

  cfg.status.tokenCnt := coreArea(if (numOfCore == 4) 1 else 0).core.toAxiLite.tokenCnt
  cfg.status.argMaxVld := coreArea(if (numOfCore == 4) 1 else 0).core.toAxiLite.argMaxVld
  cfg.status.argMaxIndex := coreArea(if (numOfCore == 4) 1 else 0).core.toAxiLite.argMaxIndex
  cfg.status.prefill := coreArea(if (numOfCore == 4) 1 else 0).core.toAxiLite.prefill
  cfg.status.layerCnt := coreArea(if (numOfCore == 4) 1 else 0).core.toAxiLite.layerCnt
  cfg.status.projectionDone := coreArea(if (numOfCore == 4) 1 else 0).core.toAxiLite.projectionDone
  cfg.status.projectionError := coreArea(if (numOfCore == 4) 1 else 0).core.toAxiLite.projectionError
  cfg.status.perfWeightBytes := coreArea(if (numOfCore == 4) 1 else 0).core.toAxiLite.perfWeightBytes
  cfg.status.perfKvReadBytes := coreArea(if (numOfCore == 4) 1 else 0).core.toAxiLite.perfKvReadBytes
  cfg.status.perfKvWriteBytes := coreArea(if (numOfCore == 4) 1 else 0).core.toAxiLite.perfKvWriteBytes
  cfg.status.perfVerifyCycles := coreArea(if (numOfCore == 4) 1 else 0).core.toAxiLite.perfVerifyCycles
  cfg.status.perfMemoryStallCycles := coreArea(if (numOfCore == 4) 1 else 0).core.toAxiLite.perfMemoryStallCycles

  for (i <- 0 until numOfCore) {
    if (m_axi(i) != null) Axi4SpecRenamer(m_axi(i))
    if (m_axi_hp(i) != null) {
      for (j <- 0 until DMA_SPLIT(i)) {
        Axi4SpecRenamer(m_axi_hp(i)(j))
      }
    }
  }

  if (numOfCore != 1) {
    if (sync) {
      (coreArea, coreArea.drop(1) ++ List(coreArea.head)).zipped.foreach { (a, b) =>
        b.core.c2c.to.toStream >> a.core.c2c.from
      }
    }
    else {
      (coreArea, coreArea.drop(1) ++ List(coreArea.head)).zipped.foreach { (a, b) =>
        b.core.c2c.to.toStream.queue(size = 32, pushClock = b.clockDomain, popClock = a.clockDomain) >> a.core.c2c.from
      }
    }
  }
}
