package top

import adapter.FlowMux
import attn.KvScaleZeroPacker
import busdemux.AxiBusDistributor
import cfgGen._
import mlp._
import residual._
import spinal.core.Component.push
import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axis._
import util._
import spinal.lib.bus.amba4.axi._
import spinal.lib.bus.amba4.axis._

import scala.language.postfixOps

class DataPath(
                baseAddr: Int,
                cmdAddrWidth: Int,
                splitBaseAddr: (BigInt, BigInt),
                id: Int,
                numOfCore: Int,

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
                dataMoverSplit: Int = 1
              ) extends Component {

  val width = 16
  val bankLen = busWidth / 4
  val parallelWidth = width * bankLen
  val kvQuantWidth = 8
  val quantMaxIntFP16Init = 0x5bf8
  val idWidth = log2Up(numOfCore)

  val c2c = if (numOfCore != 1) new Bundle {
    val from = slave(Stream(Fragment(util.AxiFrame(Bits(width bits), userBit = 6, destBit = idWidth))))
    val to = master(Flow(Fragment(util.AxiFrame(Bits(width bits), userBit = 6, destBit = idWidth))))
  } else null

  val cmdGen = new GenMemCmdLenAlign(
    numOfCore = numOfCore,
    busWidth = busWidth,
    dim = dim,
    mlpDim = mlpDim,
    predDim = predDim,
    head = head,
    layer = layer,
    vocabSize = vocabSize,
    maxToken = maxToken,
    baseAddr = baseAddr,
    extTokenTag = tokenTag,
    dmaSplit = dataMoverSplit
  )

  val toAxiLite = new Bundle {
    val tokenCnt = out Bits (16 bits)
    val argMaxVld = out Bool()
    val argMaxIndex = out Bits (16 bits)
    val prefill = out Bool()
    val layerCnt = out Bits (8 bits)
    val projectionDone = out Bool()
    val projectionError = out Bool()
    val projectionDoneTag = out Bits(6 bits)
    val projectionDoneLayer = out UInt(8 bits)
    val attentionDone = out Bool()
    val mlpActivationDone = out Bool()
    val descriptorActive = out Bool()
    val speculativeKvWritesDrained = out Bool()
    val speculativeKvWriteError = out Bool()
    val perfWeightBytes = out UInt(64 bits)
    val perfKvReadBytes = out UInt(64 bits)
    val perfKvWriteBytes = out UInt(64 bits)
    val perfVerifyCycles = out UInt(64 bits)
    val perfMemoryStallCycles = out UInt(64 bits)
  }

  val tokenIndex = slave(Stream(util.AxiFrame(Bits(16 bits), userBit = 6)))
  val speculativeBatch = slave(Stream(util.SpeculativeBatchDescriptor()))
  val speculativeEnable = in Bool()
  val speculativeActive = in Bool()
  val speculativeQuery = in UInt(2 bits)
  val speculativeCommitted = in UInt(log2Up(maxToken + 1) bits)
  val speculativeBase = in UInt(log2Up(maxToken + 1) bits)
  val speculativeK = in UInt(3 bits)
  val speculativeEpoch = in UInt(8 bits)
  val perfWindowActive = in Bool()
  val perfWindowClear = in Bool()

  // The command generator owns descriptor acceptance.  Latching the same
  // descriptor here gives the production MulAdd input front-end a stable
  // GEMV/GEMM mode and K value without changing GenMemCmd/AxiLite interfaces.
  val speculativeBatchEnabled = Bool().setAsReg().init(False)
  val speculativeBatchK = UInt(3 bits).setAsReg().init(1)
  when(speculativeBatch.fire) {
    // Freeze the effective engine mode at the descriptor boundary.  The
    // software enable register may be changed only for a later transaction;
    // it must never switch RAM addressing while the current K-row tile is in
    // flight.
    speculativeBatchEnabled := speculativeEnable && speculativeBatch.payload.mode
    speculativeBatchK := speculativeBatch.payload.k
  }
  val tokenIndexPipe = tokenIndex.toFlow.m2sPipe
  val tokenKind = tokenIndexPipe.tuser.takeLow(4).resize(6)
  // One speculative transaction contributes K embedding DMA commands but
  // owns a single transformer control context. Only q=0 may enqueue the
  // legacy prefill/decode state token; subsequent q rows belong to the same
  // batched activation tile.
  val tokenControlFire = tokenIndexPipe.fire &&
    (!speculativeActive || tokenIndexPipe.tuser(5 downto 4) === 0)

  //  val attnQKVSplit = in UInt(4 bits) addTag (crossClockDomain)
  //  val attnOSplit = in UInt(4 bits) addTag (crossClockDomain)
  //  val mlpDenseGSplit = in UInt(4 bits) addTag (crossClockDomain)
  //  val lgSplit = in UInt(4 bits) addTag (crossClockDomain)
  //
  //  val attnQKVSplitPipe = Delay(attnQKVSplit, 2)
  //  val attnOSplitPipe = Delay(attnOSplit, 2)
  //  val mlpDenseGSplitPipe = Delay(mlpDenseGSplit, 2)
  //  val lgSplitPipe = Delay(lgSplit, 2)
  //
  //  cmdGen.status.attnQKVSplit := attnQKVSplitPipe
  //  cmdGen.status.attnOSplit := attnOSplitPipe
  //  cmdGen.status.mlpDenseGSplit := mlpDenseGSplitPipe
  //  cmdGen.status.lgSplit := lgSplitPipe

  val tokenIndexFifo = new StreamFifo(tokenIndex.payloadType, 64, forFMax = true)
  tokenIndexFifo.io.push.valid := tokenIndexPipe.valid
  tokenIndexFifo.io.push.tdata := tokenIndexPipe.tdata
  tokenIndexFifo.io.push.tuser := tokenIndexPipe.tuser
  cmdGen.io.tokenIndex << tokenIndexFifo.io.pop

  cmdGen.io.speculativeBatch << speculativeBatch

  val projectionRetirement = new SpeculativeProjectionRetirement(bankLen = bankLen)
  projectionRetirement.io.launch.valid := speculativeBatch.fire
  projectionRetirement.io.launch.payload := speculativeBatch.payload

  val m_axi = if (dataMoverSplit == 1) master(Axi4(
    Axi4Config(
      addressWidth = cmdAddrWidth,
      dataWidth = busWidth,
      idWidth = 4,
      useBurst = true,
      useCache = true,
      useId = true,
      useLen = true,
      useProt = true,
      useSize = true,
      arUserWidth = 4,
      awUserWidth = 4,
      useLast = true,
      useResp = true,
      useLock = false,
      useQos = false,
      useRegion = false
    )
  )) else null

  val m_axi_hp = if (dataMoverSplit > 1) Vec(master(Axi4(
    Axi4Config(
      addressWidth = cmdAddrWidth,
      dataWidth = busWidth / dataMoverSplit,
      idWidth = 4,
      useBurst = true,
      useCache = true,
      useId = true,
      useLen = true,
      useProt = true,
      useSize = true,
      arUserWidth = 4,
      awUserWidth = 4,
      useLast = true,
      useResp = true,
      useLock = false,
      useQos = false,
      useRegion = false
    ))), dataMoverSplit) else null

  val aresetn = in Bool()

  val postFix = if (cmdAddrWidth == 32) "" else "b" + cmdAddrWidth.toString
  val dmaMig = if (dataMoverSplit == 1) new AXIDataMoverWrapper(
    busWidth, busWidth, busWidth,
    "AxiDatamover" + busWidth.toString + postFix, cmdBytes = 5 + cmdAddrWidth / 8
  ) else null

  //  val s2mmStsVld = if (dataMoverSplit == 1) Bool() else null
  //  val s2mmStsData = if (dataMoverSplit == 1) Bits(8 bits) else null
  //  val mm2sStsVld = if (dataMoverSplit == 1) Bool() else null
  //  val mm2sStsData = if (dataMoverSplit == 1) Bits(8 bits) else null
  //  if (dataMoverSplit == 1) {
  //    s2mmStsVld := dmaMig.io.m_axis_s2mm_sts.valid
  //    s2mmStsData := dmaMig.io.m_axis_s2mm_sts.data
  //    mm2sStsVld := dmaMig.io.m_axis_mm2s_sts.valid
  //    mm2sStsData := dmaMig.io.m_axis_mm2s_sts.data
  //
  //    s2mmStsVld.addAttribute("mark_debug", "true")
  //    s2mmStsData.addAttribute("mark_debug", "true")
  //    mm2sStsVld.addAttribute("mark_debug", "true")
  //    mm2sStsData.addAttribute("mark_debug", "true")
  //  }

  val kvCacheLen = dim / head * maxToken / dataMoverSplit
  val kvSzLen = 4 * maxToken / dataMoverSplit
  val mlpULen = cmdGen.mmap.mlpDenseU_totalLen / dataMoverSplit
  val mlpDLen = cmdGen.mmap.mlpDenseD_totalLen / dataMoverSplit

  val splitMapList = List(0, kvCacheLen, kvSzLen, 0, mlpULen.toInt, mlpDLen.toInt, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
  val dmaHp = if (dataMoverSplit > 1) new SplitAxiDatamover(
    busWidth, dataMoverSplit, 512, offsetTable = splitMapList, addressWidth = cmdAddrWidth
  ) else null

  //  val mm2sCmdReMap = if (cmdAddrWidth != 32) new AddressRemap(splitBaseAddr._1, splitBaseAddr._2, 32, cmdAddrWidth, cmdGen.mmap.denseWhereToSplit) else null
  //  val s2mmCmdReMap = if (cmdAddrWidth != 32) new AddressRemap(splitBaseAddr._1, splitBaseAddr._2, 32, cmdAddrWidth, cmdGen.mmap.denseWhereToSplit) else null

  val mm2sCmdReMap = if (cmdAddrWidth != 32) new AddressRemap(splitBaseAddr._1, splitBaseAddr._2, 32, cmdAddrWidth, cmdGen.mmap.denseWhereToSplit) else null
  val s2mmCmdReMap = if (cmdAddrWidth != 32) new AddressRemap(splitBaseAddr._1, splitBaseAddr._2, 32, cmdAddrWidth, cmdGen.mmap.denseWhereToSplit) else null

  if (cmdAddrWidth != 32) {
    mm2sCmdReMap.io.input << cmdGen.io.mm2sCmd
    s2mmCmdReMap.io.input << cmdGen.io.s2mmCmd
    println("first bank:", cmdGen.mmap.denseWhereToSplit, "second bank", cmdGen.mmap.denseTotalMem - cmdGen.mmap.denseWhereToSplit)
  }

  val mm2sCmdLocal = if (cmdAddrWidth == 32) cmdGen.io.mm2sCmd else mm2sCmdReMap.io.output
  val s2mmCmdLocal = if (cmdAddrWidth == 32) cmdGen.io.s2mmCmd else s2mmCmdReMap.io.output

  // Physical DataMover transaction terminals. These are assigned in the
  // selected split/non-split implementation below and feed epoch drain
  // trackers after the actual command handshake and status response.
  val physicalMm2sCmdFire = Bool()
  val physicalS2mmCmdFire = Bool()
  val physicalMm2sStatusFire = Bool()
  val physicalS2mmStatusFire = Bool()
  val physicalDmaError = Bool()

  if (dataMoverSplit == 1) {
    dmaMig.io.m_axi_s2mm_aresetn := aresetn
    dmaMig.io.m_axi_mm2s_aresetn := aresetn
    dmaMig.io.m_axis_s2mm_cmdsts_aresetn := aresetn
    dmaMig.io.m_axis_mm2s_cmdsts_aresetn := aresetn

    cmdGen.io.mm2s << dmaMig.io.m_axis_mm2s
    dmaMig.io.s_axis_s2mm << cmdGen.io.s2mm.queue(512, latency = 2, forFMax = true)
    dmaMig.io.s_axis_mm2s_cmd.arbitrationFrom(mm2sCmdLocal)
    dmaMig.io.s_axis_mm2s_cmd.data := mm2sCmdLocal.payload
    dmaMig.io.s_axis_s2mm_cmd.arbitrationFrom(s2mmCmdLocal)
    dmaMig.io.s_axis_s2mm_cmd.data := s2mmCmdLocal.payload

    m_axi << dmaMig.io.m_axi
    dmaMig.io.m_axi.r.id.removeAssignments()
    dmaMig.io.m_axi.b.id.removeAssignments()
    dmaMig.io.m_axis_s2mm_sts.ready := True
    dmaMig.io.m_axis_mm2s_sts.ready := True
    physicalMm2sCmdFire := dmaMig.io.s_axis_mm2s_cmd.fire && speculativeActive
    physicalS2mmCmdFire := dmaMig.io.s_axis_s2mm_cmd.fire && speculativeActive
    physicalMm2sStatusFire := dmaMig.io.m_axis_mm2s_sts.fire
    physicalS2mmStatusFire := dmaMig.io.m_axis_s2mm_sts.fire
    physicalDmaError := dmaMig.io.mm2s_err || dmaMig.io.s2mm_err
  }

  if (dataMoverSplit > 1) {
    dmaHp.aresetn := aresetn
    dmaHp.io.mm2s >> cmdGen.io.mm2s
    dmaHp.io.s2mm << cmdGen.io.s2mm
    dmaHp.io.mm2sCmd << mm2sCmdLocal
    dmaHp.io.s2mmCmd << s2mmCmdLocal
    dmaHp.io.mm2sStatus.ready := True
    dmaHp.io.s2mmStatus.ready := True
    physicalMm2sCmdFire := dmaHp.io.mm2sCmd.fire && speculativeActive
    physicalS2mmCmdFire := dmaHp.io.s2mmCmd.fire && speculativeActive
    physicalMm2sStatusFire := dmaHp.io.mm2sStatus.fire
    physicalS2mmStatusFire := dmaHp.io.s2mmStatus.fire
    physicalDmaError := dmaHp.io.mm2sError || dmaHp.io.s2mmError
    (m_axi_hp, dmaHp.io.m_axi).zipped.foreach(_ << _)
  }

  val physicalMm2sTracker = new util.SpeculativeOutstandingTracker()
  physicalMm2sTracker.io.active := speculativeActive
  physicalMm2sTracker.io.epoch := speculativeEpoch
  physicalMm2sTracker.io.issue := physicalMm2sCmdFire
  physicalMm2sTracker.io.retire := physicalMm2sStatusFire && !physicalMm2sTracker.io.drained
  physicalMm2sTracker.io.clearError := !speculativeActive && physicalMm2sTracker.io.drained

  val physicalS2mmTracker = new util.SpeculativeOutstandingTracker()
  physicalS2mmTracker.io.active := speculativeActive
  physicalS2mmTracker.io.epoch := speculativeEpoch
  physicalS2mmTracker.io.issue := physicalS2mmCmdFire
  physicalS2mmTracker.io.retire := physicalS2mmStatusFire && !physicalS2mmTracker.io.drained
  physicalS2mmTracker.io.clearError := !speculativeActive && physicalS2mmTracker.io.drained

  val axi = new AxiBusDistributor(
    busWidth = busWidth,
    dim = dim,
    numOfCore = numOfCore,
    scaleBanks = dim / numOfCore * 16 / busWidth,
    maxToken = maxToken,
    lnScaleBusTag = lnScaleBusTag,
    denseBusTag = denseBusTag,
    kvCacheBusTag = kvCacheBusTag,
    sparseDotTagMap = sparseDotTagMap,
    sparseAxpyTagMap = sparseAxpyTagMap,
    denseCfgTag = denseCfgTag,
    sparseCfgTag = sparseCfgTag,
    lnSqrCfgTag = lnSqrCfgTag,
    kvCfgTag = kvCfgTag,
    mlpGTag = mlpGTag,
    fp16ToFp32 = fp16toFp32_func_block
  )

  val attn = new AttnSubMod(
    busWidth = busWidth,
    head = head,
    numOfCore = numOfCore,
    headDim = headDim,
    sqrtHeadDim = sqrtHeadDim,
    ropePoint = ropePoint,
    quantWidth = kvQuantWidth,
    quantMaxIntFP16Init = quantMaxIntFP16Init,
    maxToken = maxToken,
    ropeTagMap = ropeTagMap,
    quantTagMap = quantTagMap,
    softmaxTag = softmaxTag,
    qkMulTag = qkMulTag,
    mul_func = mul_func,
    add_func = add_func,
    sub_func = sub_func,
    div_func = div_func,
    acc_func = acc_func,
    highPcs_mul_func = rope_highPcs_mul_func,
    toInt_func = rope_toInt_func,
    fromInt_func = rope_fromInt_func,
    lt_func = lt_func,
    convert_func = quant_conv_func,
    exp_func = expo_func,
    mix_mul_func = mix_mul_func,
    add_conv_func = add_conv_func,
    fp32ToFp16Latency = toFp16_latency,
    fp16ToFp32 = fp16toFp32_func,
    fp32ToFp16 = fp32toFp16_func,
    fp32_lt_func = fp32lt_func,
    fp32_sub_func = fp32sub_func,
    fp32_acc_func = fp32acc_func,
    fp32_div_func = fp32div_func,
    fp32_exp_func = fp32exp_func
  )

  val ln = new NormSubModNew(
    id = id,
    dim = dim,
    numOfCore = numOfCore,
    lnInTagMap = lnInTagMap,
    attnLnTag = attnLnTag,
    logitsLnTag = logitsLnTag,
    fp16toFp32_func = fp16toFp32_func,
    fp16toFp32_func_block = fp16toFp32_func_block,
    fp32toFp16_func = fp32toFp16_func,
    fp32mul_func = fp32mul_func,
    fp32mul_func_block = fp32mul_func_block,
    fp32acc_func = fp32acc_func,
    fp32rsqrt_func = fp32rsqrt_func
  )

  val sOut = new ScalarOutSubMod(
    mlpDim = mlpDim,
    width = width,
    actLatency = 0,
    //    siluLatency = exp_latency + add_latency + div_latency + 1,
    siluLatency = fp32Exp_latency + fp32Add_latency + fp32Div_latency + toFp32_latency + toFp16_latency,
    index2UgTag = index2UgTag,
    ugMulTag = ugMulTag,
    filterTagMap = zeroFilterTagMap,
    tagSeqLenMap = zeroFilterTag2SeqLen,
    axpyInTag = axpyTensorInTag,
    act_func = act_func,
    mul_func = mul_func,
    lt_func = lt_func
  )

  val busIn = new BusInSubModNew(
    busWidth = busWidth,
    width = width,
    bankLen = bankLen,
    numOfCore = numOfCore,
    dim = dim,
    layer = layer,
    split = sgSplit,
    mlpOutTag = vecIn2ResTag.head,
    kvInTag = kvInTag,
    toResTag = vecIn2ResTag,
    toConvertTag = vecIn2ScalarTag,
    wkvOutFifoDepth = wkvOutFifoDepth,
    vecP2sFifoDepth = vecP2sFifoDepth,
    convert_latency = deQuant_latency,
    int4_conv = deQuant_int4_conv_func,
    int8_conv = deQuant_int8_conv_func
  )

  val vecOut = new VecOutSubMod(
    width = width,
    dim = dim,
    head = head,
    layer = layer,
    numOfCore = numOfCore,
    bankLen = bankLen,
    fifoDepth = vecOutFifoDepth,
    split = sgSplit,
    lnOutGateTag = lnOut2VecTag,
    dotOutGateTag = dotOut2VecTag,
    ropeOutGateTag = rope2VecTag,
    vLocalTag = vLocalTag,
    serial2VecOutTag = serial2VecOutTag,
    busIn2VecOutTag = busIn2VecOutTag,
    engine2VecOutTag = engine2VecOutTag,
    sqrtHeadDim = sqrtHeadDim,
    mul_func = mul_func
  )

  val resBuf = new ResidualBuffer(
    dim = dim,
    numOfCore = numOfCore,
    width = width,
    bankLen = bankLen,
    split = sgSplit
  )

  val resAdd = new SerialResAdd(
    id = id,
    dim = dim,
    numOfCore = numOfCore,
    width = width,
    addLatency = add_latency,
    resAddTag = resAddTag,
    add_func = add_func
  )

  val engine = new MulAddSGNew(
    numOfCore = numOfCore,
    split = sgSplit,
    width = width,
    bankLen = bankLen,
    dotMaxFirstDim = dotMaxFirstDim,
    axpyMaxFirstDim = axpyMaxFirstDim,
    mul_latency = mul_latency,
    add_latency = add_latency,
    acc_latency = acc_latency,
    add_func = add_func,
    acc_func = acc_func,
    mul_func_nonblock = mul_func,
    mul_func_block = mul_func_block,

    toFp32_func = fp16toFp32_func,
    toFp32_func_block = fp16toFp32_func_block,
    toFp16_func = fp32toFp16_func,
    fp32Mul_func = fp32mul_func,
    fp32Add_func = fp32add_func,

    fp32Acc_func = fp32acc_func,
    toFp32_latency = toFp32_latency,
    toFp16_latency = toFp16_latency,
    fp32Mul_latency = fp32Mul_latency,
    fp32Add_latency = fp32Add_latency,
    fp32Acc_latency = fp32Acc_latency
  )

  // Runtime transactions select GEMM mode even when descriptors are produced
  // by the on-chip production scheduler instead of the debug AXI-Lite port.
  engine.io.speculativeMode := speculativeActive || speculativeBatchEnabled
  engine.io.speculativeK := Mux(speculativeActive, speculativeK, speculativeBatchK)
  projectionRetirement.io.scalarValid := engine.io.scalarOut.valid
  projectionRetirement.io.scalarTag := engine.io.scalarOut.tuser
  projectionRetirement.io.vectorValid := engine.io.vecOut.valid
  projectionRetirement.io.vectorTag := engine.io.vecOut.tuser

  val node = new AllGatherSubModNew(
    id = id,
    numOfCore = numOfCore,
    width = width,
    mlpDim = mlpDim,
    resOut2NodeTag = resOut2NodeTag,
    p2sOut2NodeTag = p2sOut2NodeTag,
    dotOut2NodeTag = dotOut2NodeTag,
    index2NodeTag = index2NodeTag,
    acc_func = acc_func
  )

  val cfgInsert = new InsertCfg(
    insertTag = cfgInsertTag
  )

  val sample = new GreedySampler(
    width = width,
    vocabSize = vocabSize,
    logitsTag = logitsTag,
    lt_func = lt_func
  )

  //  val exp = new ExpFunc(
  //    port = 2,
  //    latency = exp_latency,
  //    lt_func = lt_func,
  //    exp_func = expo_func
  //  )

  //  val siluAct = new Silu(
  //    exp_latency = exp_latency,
  //    add_latency = add_latency,
  //    div_func = div_func,
  //    add_func = add_func,
  //    exp_func = expo_func
  //  )

  val siluAct = new SiluFp32(
    exp_latency = fp32Exp_latency,
    add_latency = fp32Add_latency,
    div_func = fp32div_func,
    add_func = fp32add_func,
    exp_func = fp32exp_func,
    toFp32_func = fp16toFp32_func,
    toFP16_func = fp32toFp16_func
  )

  val szPacker = new KvScaleZeroPacker(
    busWidth = busWidth,
    head = head / numOfCore,
    layer = layer
  )

  val cfgGen = new GenCfg(
    dim = dim,
    mlpDim = mlpDim,
    predDim = predDim,
    vocabSize = vocabSize,
    head = head,
    layer = layer,
    bankLen = bankLen,
    numOfCore = numOfCore,
    maxToken = maxToken
  )

  val stateGen = new StateGen(
    busInVecCnt = dim * width / busWidth / numOfCore,
    engineOutVecCnt = dim / bankLen / numOfCore,
    dotOutVecCnt = dim / numOfCore,
    layer = layer,
    head = head,
    numOfCore = numOfCore,
    maxToken = maxToken,
    lmHeadParamTag = lmHeadParamTag,
    attnVParamTag = attnVParamTag,
    mlpDParamTag = mlpDParamTag,
    tokenTag = tokenTag,
    mlpTensorTag = busIn2VecOutTag.head,
    vTensorTag = vTensorTag
  )

  // Count the real V-weighted attention output fragments. In speculative
  // mode the shared AXPY engine emits K complete vectors; only their terminal
  // may release the sequencer's V-to-O barrier.
  val speculativeAttentionCountWidth = log2Up(dim / bankLen / numOfCore * 4)
  val speculativeAttentionCount = UInt(speculativeAttentionCountWidth bits).setAsReg().init(0)
  val speculativeAttentionHit = engine.io.vecOut.valid &&
    engine.io.vecOut.tuser === B(engine2VecOutTag.head, 6 bits)
  val speculativeAttentionLast = speculativeAttentionCount ===
    (speculativeK.resize(speculativeAttentionCountWidth) *
      U(dim / bankLen / numOfCore, speculativeAttentionCountWidth bits) - 1).resized
  val speculativeAttentionDone = speculativeAttentionHit && speculativeActive && speculativeAttentionLast
  when(!speculativeActive) {
    speculativeAttentionCount.clearAll()
  } elsewhen(speculativeAttentionHit) {
    when(speculativeAttentionLast) {
      speculativeAttentionCount.clearAll()
    } otherwise {
      speculativeAttentionCount := speculativeAttentionCount + 1
    }
  }

  //  stateGen.status.layerCnt.addAttribute("mark_debug","true")
  //  stateGen.status.token.addAttribute("mark_debug", "true")
  //  axi.int.bus.tuser.addAttribute("mark_debug", "true")
  //  engine.io.scalarOut.addAttribute("mark_debug", "true")

  cmdGen.local.bus >> axi.io.bus
  node.io.indexOut >> cmdGen.local.index
  cmdGen.local.kvBus.arbitrationFrom(szPacker.io.kvBus)
  cmdGen.local.kvBus.last := szPacker.io.kvBus.last
  cmdGen.local.kvBus.data := szPacker.io.kvBus.fragment
  cmdGen.local.kvBus.dest.clearAll()
  cmdGen.status.enPredictor := stateGen.status.enPredictor
  cmdGen.status.speculativeEnable := speculativeActive
  cmdGen.status.speculativeQuery := speculativeQuery
  cmdGen.status.speculativeCommitted := speculativeBase
  cmdGen.status.speculativeK := speculativeK
  cmdGen.status.speculativeEpoch := speculativeEpoch
  cmdGen.status.projectionRetire := projectionRetirement.io.done.valid
  cmdGen.status.projectionRetireTag := projectionRetirement.io.done.projectionTag
  cmdGen.status.projectionRetireLayer := projectionRetirement.io.done.layerId
  cmdGen.status.perfWindowActive := perfWindowActive
  cmdGen.status.perfWindowClear := perfWindowClear
  cmdGen.status.perfKvReadBeat := axi.int.bus.fire &&
    kvCacheBusTag.map(tag => axi.int.bus.tuser === tag).reduce(_ || _)

  // P4 production manager hookup is intentionally explicit.  Until its KV
  // requester and V-AXPY terminal event are connected, keep the speculative
  // controller inert so the legacy attention datapath remains unchanged.
  attn.io.p4.start.valid := False
  attn.io.p4.start.payload.k := 1
  attn.io.p4.start.payload.committedTokens := 0
  attn.io.p4.tile.ready := False
  attn.io.p4.softmax.ready := False
  attn.io.p4.vAxpyTileOut.valid := False
  attn.io.p4.vAxpyTileOut.fragment.tdata.clearAll()
  attn.io.p4.vAxpyTileOut.fragment.tuser.clearAll()
  attn.io.p4.vAxpyTileOut.last := False

  // from io

  attn.io.dotOut << engine.io.scalarOut
  attn.status.speculativeEnable := speculativeActive
  attn.status.speculativeQuery := cmdGen.status.speculativeQueryActive
  attn.status.speculativeCommitted := speculativeBase

  //  exp.io.inputs(0) << attn.exp.to
  //  exp.io.outputs(0) >> attn.exp.from
  //  exp.io.inputs(1) << siluAct.exp.to
  //  exp.io.outputs(1) >> siluAct.exp.from

  siluAct.io.in << sOut.silu.to
  siluAct.io.out >> sOut.silu.from

  sOut.io.zfIndexOut >> node.io.indexIn
  sOut.io.allReduceOut << node.io.allReduceOut
  sOut.io.scalarOut >> engine.io.axpyIn

  ln.io.allGatherOut << node.io.allGatherOut
  ln.io.allReduceOut << node.io.allReduceOut
  busIn.io.p2sOut >> node.io.p2sOut

  axi.int.lnScale >> ln.io.lnScale
  axi.int.preScale >> engine.io.preScale
  axi.int.postScale >> engine.io.postScale
  axi.int.zeroInt4 >> busIn.io.zeroInt4
  axi.int.zeroInt8 >> busIn.io.zeroInt8

  busIn.io.bus << axi.int.bus
  busIn.io.wkv >> engine.io.wkvIn
  busIn.io.vecIn << engine.io.vecOut

  vecOut.io.dotOut << engine.io.scalarOut
  vecOut.io.vecOut >> engine.io.dotIn
  vecOut.io.engineVecIn << engine.io.vecOut

  resBuf.io.parallelOut >> engine.io.resAdd

  resAdd.io.dotOut << engine.io.scalarOut
  resAdd.io.output >> node.io.resOut
  if (numOfCore != 1) resAdd.io.fromAllReduce << node.io.allReduceOut

  engine.io.scalarOut >> node.io.dotOut

  cfgInsert.io.cfgIn << cfgGen.io.cfg
  cfgInsert.io.cfgOut >> engine.io.cfg
  cfgInsert.io.index << node.io.indexOut

  if (numOfCore != 1) {
    node.c2c.to >> c2c.to
    node.c2c.from << c2c.from
  }

  // submodules interconnection

  vecOut.io.lnOut << ln.io.lnOut
  vecOut.io.vLocal >> busIn.io.vLocal

  busIn.io.toResBuf >> resBuf.io.parallelIn
  busIn.io.directOut >> vecOut.io.busVecIn

  resBuf.io.serialOut >> resAdd.io.fromResBuf
  resBuf.io.serialIn << resAdd.io.toResBuf

  sOut.io.p2sOut.clearAll()
  sOut.io.gateIndexOut >> node.io.gateIndexIn
  sOut.io.ugIndexOut >> node.io.ugIndexIn
  sOut.io.allGatherIndexIn << node.io.indexOut

  attn.io.ropeOut >> vecOut.io.ropeOut
  attn.io.softmaxOut >> sOut.io.softmaxOut

  sample.io.logits << node.io.allReduceOut

  stateGen.io.busIn.valid := axi.int.bus.fire
  stateGen.io.busIn.fragment := axi.int.bus.tuser
  stateGen.io.busIn.last := axi.int.bus.last
  stateGen.io.gtCnt << cfgInsert.io.gtCnt
  stateGen.io.tokenIndexFlow.valid := tokenControlFire
  stateGen.io.tokenIndexFlow.payload := tokenKind
  stateGen.io.engineOut.valid := engine.io.vecOut.valid
  stateGen.io.engineOut.payload := engine.io.vecOut.tuser
  stateGen.io.dotOut.valid := engine.io.scalarOut.valid
  stateGen.io.dotOut.payload := engine.io.scalarOut.tuser
  stateGen.status.argmaxVld := sample.io.argmax.valid
  stateGen.status.endOfDecode := sample.io.endOfDecode
  stateGen.io.projectionDone := cmdGen.status.projectionDone
  stateGen.io.projectionError := cmdGen.status.projectionError

  szPacker.io.qScale << attn.io.quantScale
  szPacker.io.qZero << attn.io.quantZero
  szPacker.io.qOut << attn.io.afterQuant
  szPacker.io.kSzOut >> axi.io.kSzOut
  szPacker.io.vSzOut >> axi.io.vSzOut
  szPacker.io.nextLayer := stateGen.status.nextLayer
  szPacker.io.tokenIndexFlow.valid := tokenControlFire
  szPacker.io.tokenIndexFlow.payload := tokenKind
  szPacker.io.tokenPosition := Mux(
    speculativeActive,
    (speculativeBase + cmdGen.status.speculativeQueryActive.resized).resized,
    stateGen.status.token
  ).resized

  axi.io.token := stateGen.status.token
  axi.io.enPredictor := stateGen.status.enPredictor
  axi.io.postCfgTag := engine.io.postCfgTag
  axi.io.preCfgTag := engine.io.preCfgTag

  busIn.status.tokenNextHit := stateGen.status.tokenNextHit
  busIn.status.mlpNextHit := stateGen.status.mlpNextHit
  busIn.status.vNextHit := stateGen.status.vNextHit
  busIn.status.flushRes := stateGen.status.flushRes
  busIn.status.logitsGen := stateGen.status.logitsGen

  attn.status.token := stateGen.status.token
  ln.status.toLogitsGen := stateGen.status.toLogitsGen

  vecOut.status.tokenIndexFlow.valid := tokenControlFire
  vecOut.status.tokenIndexFlow.payload := tokenKind

  sOut.status.enPredictor := stateGen.status.enPredictor
  cfgInsert.status.enPredictor := stateGen.status.enPredictor
  vecOut.status.enPredictor := stateGen.status.enPredictor

  cfgGen.status.enPredictor := stateGen.status.enPredictor
  cfgGen.status.tokenIndexFlow.valid := tokenControlFire
  cfgGen.status.tokenIndexFlow.payload := tokenKind

  toAxiLite.tokenCnt := stateGen.status.token.asBits.resized
  toAxiLite.argMaxVld := sample.io.argmax.valid
  toAxiLite.argMaxIndex := sample.io.argmax.payload
  toAxiLite.prefill := stateGen.status.prefill
  toAxiLite.layerCnt := stateGen.status.layerCnt.asBits.resized
  toAxiLite.projectionDone := stateGen.status.projectionDone
  toAxiLite.projectionError := stateGen.status.projectionError
  toAxiLite.projectionDoneTag := cmdGen.status.projectionDoneTag
  toAxiLite.projectionDoneLayer := cmdGen.status.projectionDoneLayer
  toAxiLite.attentionDone := speculativeAttentionDone
  toAxiLite.mlpActivationDone := sOut.mlpActivationDone && speculativeActive
  toAxiLite.speculativeKvWritesDrained := cmdGen.status.speculativeKvWritesDrained &&
    physicalMm2sTracker.io.drained && physicalS2mmTracker.io.drained
  toAxiLite.speculativeKvWriteError := cmdGen.status.speculativeKvWriteError ||
    physicalMm2sTracker.io.error || physicalS2mmTracker.io.error || physicalDmaError
  toAxiLite.descriptorActive := cmdGen.status.descriptorActive
  toAxiLite.perfWeightBytes := cmdGen.status.perfWeightBytes
  toAxiLite.perfKvReadBytes := cmdGen.status.perfKvReadBytes
  toAxiLite.perfKvWriteBytes := cmdGen.status.perfKvWriteBytes
  toAxiLite.perfVerifyCycles := cmdGen.status.perfVerifyCycles
  toAxiLite.perfMemoryStallCycles := cmdGen.status.perfMemoryStallCycles

  val cmdSel = if (numOfCore == 1 & dataMoverSplit == 1 || numOfCore == 4) in UInt (2 bits) addTag (crossClockDomain) else null
  if (numOfCore == 1 & dataMoverSplit == 1 || numOfCore == 4) {
    val cmdSelPipe = RegNext(RegNext(cmdSel))
    val enFatRelu = RegNext(RegNext(cmdSel =/= 0))
    cmdGen.status.cmdSel := cmdSelPipe
    sOut.status.enFatRelu := enFatRelu
  }
  else {
    sOut.status.enFatRelu.clear()
  }
}
