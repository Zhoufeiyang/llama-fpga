package cfgGen

import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axis._
import util.{GenAxiDataMoverCmd, StreamFifoPipe}

import scala.collection.mutable.ArrayBuffer
import scala.language.postfixOps

class GenMemCmdLenAlign(
                         numOfCore: Int,
                         busWidth: Int,
                         dim: Int,
                         mlpDim: Int,
                         predDim: Int,
                         head: Int,
                         layer: Int,
                         vocabSize: Int,
                         maxToken: Int,
                         baseAddr: Int,
                         extTokenTag: (Int, Int, Int),
                         dmaSplit: Int = 1,
                         pageSize: Int = 8192
                       ) extends Component {

  val bankLen = busWidth / 4

  val dataMoverAxisCfg = Axi4StreamConfig(
    dataWidth = busWidth / 8,
    useLast = true,
    useKeep = true
  )

  val localAxisCfg = Axi4StreamConfig(
    dataWidth = busWidth / 8,
    destWidth = 6,
    useDest = true,
    useLast = true
  )

  val io = new Bundle {
    val tokenIndex = slave(Stream(util.AxiFrame(Bits(16 bits), userBit = 6)))
    val speculativeBatch = slave(Stream(util.SpeculativeBatchDescriptor()))
    val mm2s = slave(Axi4Stream(dataMoverAxisCfg))
    val s2mm = master(Axi4Stream(dataMoverAxisCfg))
    val mm2sCmd = master(Stream(Bits(72 bits)))
    val s2mmCmd = master(Stream(Bits(72 bits)))
  }

  val local = new Bundle {
    val bus = master(Axi4Stream(localAxisCfg))
    val kvBus = slave(Axi4Stream(localAxisCfg))
    val index = slave(Flow(Fragment(util.AxiFrame(Bits(16 bits), userBit = 6))))
  }

  val status = new Bundle {
    val enPredictor = in Bool()
    val speculativeEnable = in Bool()
    val speculativeQuery = in UInt(2 bits)
    val speculativeCommitted = in UInt(log2Up(maxToken) bits)
    val cmdSel = if (numOfCore == 1 && dmaSplit == 1 || numOfCore == 4) in UInt (2 bits) else null
    val projectionDone = out Bool()
    val projectionError = out Bool()
    // Completion identity is returned with the sticky projection-done state
    // so an external production sequencer can retire only the descriptor it
    // issued.  The fields are latched at descriptor acceptance.
    val projectionDoneTag = out Bits(6 bits)
    val projectionDoneLayer = out UInt(8 bits)
    val descriptorActive = out Bool()
    val perfKvReadBeat = in Bool()
    val perfWindowActive = in Bool()
    val perfWindowClear = in Bool()
    val perfWeightBytes = out UInt(64 bits)
    val perfKvReadBytes = out UInt(64 bits)
    val perfKvWriteBytes = out UInt(64 bits)
    val perfVerifyCycles = out UInt(64 bits)
    val perfMemoryStallCycles = out UInt(64 bits)
  }

  import LLaMA2_7B._

  val token = UInt(log2Up(maxToken) bits).setAsReg().init(0)
  val descriptorActive = Bool().setAsReg().init(False)
  val descriptorMode = Bool().setAsReg().init(False)
  val descriptorK = UInt(3 bits).setAsReg().init(1)
  val descriptorProjectionTag = Bits(6 bits).setAsReg().init(0)
  val descriptorLayerId = UInt(8 bits).setAsReg().init(0)
  val descriptorRows = UInt(16 bits).setAsReg().init(0)
  val descriptorBeatsPerRow = UInt(16 bits).setAsReg().init(0)
  val descriptorRowCnt = UInt(16 bits).setAsReg().init(0)
  val descriptorBeatCnt = UInt(16 bits).setAsReg().init(0)
  val projectionDone = Bool().setAsReg().init(False)
  val projectionError = Bool().setAsReg().init(False)

  io.speculativeBatch.ready := !descriptorActive
  val descriptorShapeValid = io.speculativeBatch.payload.k >= 1 && io.speculativeBatch.payload.k <= 4 &&
    io.speculativeBatch.payload.rows =/= 0 && io.speculativeBatch.payload.beatsPerRow =/= 0 &&
    io.speculativeBatch.payload.layerId < layer &&
    // rows/beats are the physical streamed matrix geometry.  Candidate count
    // K is consumed by the shared GEMM datapath and must not make a valid
    // LM-head matrix (32000 rows at K=4) fail this physical-shape check.
    io.speculativeBatch.payload.rows <= 65535
  when(io.speculativeBatch.fire) {
    when(descriptorShapeValid) {
      descriptorActive.set()
      descriptorMode := io.speculativeBatch.payload.mode
      descriptorK := io.speculativeBatch.payload.k
      descriptorProjectionTag := io.speculativeBatch.payload.projectionTag
      descriptorLayerId := io.speculativeBatch.payload.layerId
      // rows x beatsPerRow is the physical target-weight stream and must not
      // grow with K. K is consumed by the shared verification datapath.
      descriptorRows := io.speculativeBatch.payload.rows
      descriptorBeatsPerRow := io.speculativeBatch.payload.beatsPerRow
      descriptorRowCnt.clearAll()
      descriptorBeatCnt.clearAll()
      projectionDone.clear()
      projectionError.clear()
    } otherwise {
      projectionError.set()
    }
  }
  // During verification, all KV reads use the frozen committed prefix plus
  // candidate q. The legacy counter remains untouched for target-only mode.
  val kvReadPosition = new SpeculativeKvPosition(maxToken)
  kvReadPosition.io.legacyPosition := token
  kvReadPosition.io.speculativeEnable := status.speculativeEnable
  kvReadPosition.io.committedPosition := status.speculativeCommitted
  kvReadPosition.io.candidatePosition := status.speculativeQuery
  val kvReadToken = kvReadPosition.io.selectedPosition
  val tokenHigh = token.dropLow(log2Up(busWidth / 32)).asUInt
  val tokenLow = token.takeLow(log2Up(busWidth / 32))
  val firstToken = token === 0
  val noSzFromMem = tokenHigh === 0

  val mmap = new LLaMA2_7B_MMAP_Align(
    dim = dim,
    mlpDim = mlpDim,
    predDim = predDim,
    layer = layer,
    head = head,
    vocabSize = vocabSize,
    numOfCore = numOfCore,
    busWidth = busWidth,
    maxToken = maxToken,
    pageSize = pageSize
  )

  val mallocPerHead = U(mmap.attnQKV_len)
  val headBase = UInt(32 bits).setAsReg().init(mmap.attnQKV_addr)

  val headBaseNext = UInt(32 bits)
  val enIncHead = Bool()
  val headCnt = UInt(log2Up(head / numOfCore) bits).setAsReg().init(0)
  val headCntAbout2Ovf = headCnt === head / numOfCore - 2
  val headCntOvf = Bool().setAsReg().init(False)
  headBase := headBaseNext
  headBaseNext := headBase
  when(enIncHead) {
    headCnt := headCnt + 1
    headBaseNext := headBase + mallocPerHead
    when(headCntAbout2Ovf)(headCntOvf.set())
    when(headCntOvf) {
      headCnt := 0
      headBaseNext := mmap.attnQKV_addr
      headCntOvf.clear()
    }
  }

  val mallocPerLayer = Mux(status.enPredictor, U(mmap.sparseLayer_len), U(mmap.denseLayer_len))
  val layerBase = UInt(32 bits).setAsReg().init(baseAddr + mmap.afterTokenizer_addr)
  val layerBaseNext = UInt(32 bits)
  val enIncLayer = Bool()
  val layerCnt = UInt(log2Up(layer) bits).setAsReg().init(0)
  val layerCntAbout2Ovf = layerCnt === layer - 2
  val layerCntOvf = Bool().setAsReg().init(False)
  layerBase := layerBaseNext
  layerBaseNext := layerBase
  when(enIncLayer) {
    layerCnt := layerCnt + 1
    layerBaseNext := layerBase + mallocPerLayer
    when(layerCntAbout2Ovf)(layerCntOvf.set())
    when(layerCntOvf) {
      layerCnt := 0
      layerBaseNext := baseAddr + mmap.afterTokenizer_addr
      layerCntOvf.clear()
    }
  }

  val attnHeadBase = UInt(32 bits).setAsReg().init(0)
  val attnHeadBaseNext = layerBaseNext + headBaseNext
  attnHeadBase := attnHeadBaseNext

  val tokenIn = Stream(Fragment(Bits(72 bits)))
  tokenIn.arbitrationFrom(io.tokenIndex)
  tokenIn.last.set()
  tokenIn.payload := GenAxiDataMoverCmd(
    io.tokenIndex.tdata.asUInt * GenMemCmdLen.tokenIn(dim, numOfCore),
    U(GenMemCmdLen.tokenIn(dim, numOfCore)),
    U(mmap.vocabTable_addr + baseAddr),
    inc = True, eof = True
  )

  val tokenTag = Stream(Bits(6 bits))
  tokenTag.valid := tokenIn.fire
  tokenTag.payload := io.tokenIndex.tuser

  val attnLn = Stream(Fragment(Bits(72 bits)))
  attnLn.valid.set()
  attnLn.last.set()
  attnLn.payload := GenAxiDataMoverCmd(U(mmap.attnLnScale_addr), U(mmap.attnLnScale_len), layerBase, inc = True, eof = True, tag = B"0000")

  val attnLnTag = Stream(Bits(6 bits))
  attnLnTag.valid := attnLn.fire
  attnLnTag.payload := B(param.ATTN_LN_SCALE, 6 bits)

  val indexCmdGen = new Area {
    //    val sparseBaseAddrOs = Vec(U(mmap.mlpPredD_addr), U(mmap.mlpSparseG_addr), U(mmap.mlpSparseU_addr), U(mmap.mlpSparseD_addr))
    //    val sparseLen = Vec(U(mmap.mlpPredD_len), U(mmap.mlpSparseG_len), U(mmap.mlpSparseU_len), U(mmap.mlpSparseD_len))
    //    val sparseLastCnt = UInt(log2Up(sparseBaseAddrOs.length) bits).setAsReg().init(0)
    //    when(local.index.valid && local.index.last & status.enPredictor) {
    //      sparseLastCnt := sparseLastCnt + 1
    //      when(sparseLastCnt === sparseBaseAddrOs.length - 1) {
    //        sparseLastCnt := 0
    //      }
    //    }
    //    val selSparseBaseAddr = sparseBaseAddrOs(sparseLastCnt)
    //    val selSparseLen = sparseLen(sparseLastCnt)

    val denseBaseAddrOs = Vec(U(mmap.mlpDenseU_addr), U(mmap.mlpDenseD_addr))
    val denseLen = Vec(U(mmap.mlpDenseU_len), U(mmap.mlpDenseD_len))
    val denseTag = Vec(B"0100", B"0101")
    val denseLastCnt = UInt(log2Up(denseBaseAddrOs.length) bits).setAsReg().init(0)
    when(local.index.valid && local.index.last & ~status.enPredictor) {
      denseLastCnt := denseLastCnt + 1
      when(denseLastCnt === denseBaseAddrOs.length - 1) {
        denseLastCnt := 0
      }
    }
    val selDenseBaseAddr = denseBaseAddrOs(denseLastCnt)
    val selDenseLen = denseLen(denseLastCnt)
    val selDenseTag = denseTag(denseLastCnt)
    val selBaseAddr = RegNext(selDenseBaseAddr, init = U(0))
    val selLen = RegNext(selDenseLen, init = U(0))
    val selTag = RegNext(selDenseTag)

    val indexFlow = Flow(Fragment(UInt(16 bits)))
    indexFlow.valid := RegNext(local.index.valid, init = False)
    indexFlow.last := RegNext(local.index.last, init = False)
    indexFlow.fragment := RegNext(local.index.tdata.asUInt)

    val denseU = util.GenSplitAlignTransfer(mmap.mlpDenseU_addr, mmap.mlpDenseU_totalLen, layerBase, pageSize)
    val denseD = util.GenSplitAlignTransfer(mmap.mlpDenseD_addr, mmap.mlpDenseD_totalLen, layerBase, pageSize)
    val indexMux = new StreamMux(Fragment(Bits(72 bits)), 2)
    indexMux.io.inputs(0) << denseU
    indexMux.io.inputs(1) << denseD
    indexMux.io.select := denseLastCnt
    val enDenseUD = Bool().setAsReg().init(False)
    enDenseUD.setWhen(indexFlow.isFirst)
    enDenseUD.clearWhen(indexMux.io.output.isLast)

    val indexCmd = Flow(Fragment(Bits(72 bits)))

    val sparseSupportCond = numOfCore == 1 && dmaSplit == 1 || numOfCore == 4
    if (sparseSupportCond) {
      val indexCmd0 = indexMux.io.output.continueWhen(enDenseUD).toFlow
      val indexCmd1 = GenAxiDataMoverCmd.fromIndexWithPackMerge(indexFlow, layerBase + selBaseAddr, selLen, tag = selTag, dmaSplit = dmaSplit)
      val indexCmd2 = GenAxiDataMoverCmd.fromIndex(indexFlow, layerBase + selBaseAddr, selLen, tag = B"0000")
      indexCmd << Vec(indexCmd0, indexCmd1, indexCmd2)(status.cmdSel)
    }
    else {
      val indexCmd0 = indexMux.io.output.continueWhen(enDenseUD).toFlow
      indexCmd << indexCmd0
    }

    val indexCmdFifo = new StreamFifoPipe(Bits(72 bits), if (sparseSupportCond) 12288 else 8192 / numOfCore, forFMax = true)
    indexCmdFifo.logic.ram.addAttribute("ram_style", "ultra")
    indexCmdFifo.io.push.valid := indexCmd.valid
    indexCmdFifo.io.push.payload := indexCmd.fragment

    //    val indexReady = Bool()
    //    indexReady := indexCmdFifo.io.push.ready
    //    indexReady.addAttribute("mark_debug", "true")

    val indexLastFifo = new StreamFifoPipe(Bool(), if (sparseSupportCond) 12288 else 8192 / numOfCore, forFMax = true)
    indexLastFifo.io.push.valid := indexCmdFifo.io.push.fire
    indexLastFifo.io.push.payload := indexCmd.last
    indexLastFifo.io.pop.ready := indexCmdFifo.io.pop.fire

    //    val cmdCnt = UInt(16 bits).setAsReg().init(0)
    //    val cmdCntLock = UInt(16 bits).setAsReg().init(0)
    //    cmdCntLock.addAttribute("mark_debug", "true")
    //    when(indexCmd.valid){
    //      cmdCnt := cmdCnt + 1
    //      when(indexCmd.last){
    //        cmdCntLock := cmdCnt
    //        cmdCnt.clearAll()
    //      }
    //    }

    val indexCmdOut = Stream(Fragment(Bits(72 bits)))
    indexCmdOut.arbitrationFrom(indexCmdFifo.io.pop)
    indexCmdOut.fragment := indexCmdFifo.io.pop.payload
    indexCmdOut.last := indexLastFifo.io.pop.payload

    val deMux = new StreamDemux(Fragment(Bits(72 bits)), 2)
    deMux.io.input << indexCmdOut
    val toMlpDense = deMux.io.outputs(0)
    val toMlpPredict = deMux.io.outputs(1)
    deMux.io.select := status.enPredictor.asUInt
  }

  val attnKV = new Area {
    val attnKCmdVec = util.GenSplitAlignTransfer(mmap.attnK_addr, mmap.attnK_len, attnHeadBase, pageSize)
    val attnVCmdVec = util.GenSplitAlignTransfer(mmap.attnV_addr, mmap.attnV_len, attnHeadBase, pageSize)
    val ports = 2
    val mux = new StreamMux(Fragment(Bits(72 bits)), ports)
    mux.io.inputs(0) << attnKCmdVec
    mux.io.inputs(1) << attnVCmdVec
    val sel = UInt(log2Up(ports) bits).setAsReg().init(0)
    val muxOutFire = mux.io.output.fire
    val muxOutIsFirst = mux.io.output.isFirst
    val selOvf = sel === ports - 1
    mux.io.select := sel
    when(muxOutFire & mux.io.output.last) {
      sel := sel + 1
      when(selOvf) {
        sel.clearAll()
      }
    }

    val tagVec = Vec(B(param.ATTN_W_K, 6 bits), B(param.ATTN_W_V, 6 bits))
    val tagSel = UInt(log2Up(ports) bits).setAsReg().init(0)
    val tagFire = muxOutFire & muxOutIsFirst
    when(muxOutFire & muxOutIsFirst) {
      tagSel := tagSel + 1
      when(tagSel === ports - 1) {
        tagSel.clearAll()
      }
    }
    val tag = Stream(Bits(6 bits))
    tag.valid := tagFire
    tag.payload := tagVec(tagSel)
    val cmd = Stream(Fragment(Bits(72 bits)))
    cmd.arbitrationFrom(mux.io.output)
    cmd.fragment := mux.io.output.fragment
    cmd.last := mux.io.output.last & selOvf
  }

  val attnQKVNoSz = new Area {
    val attnQCmdVec = util.GenSplitAlignTransfer(mmap.attnQ_addr, mmap.attnQ_len, attnHeadBase, pageSize)
    val attnKCmdVec = util.GenSplitAlignTransfer(mmap.attnK_addr, mmap.attnK_len, attnHeadBase, pageSize)
    val attnVCmdVec = util.GenSplitAlignTransfer(mmap.attnV_addr, mmap.attnV_len, attnHeadBase, pageSize)
    val attnKCacheCmd = GenAxiDataMoverCmd.retStream(
      U(mmap.attnKCache_addr),
      GenMemCmdLen.kvCache(dim / head, kvReadToken).asUInt,
      attnHeadBase,
      inc = True, eof = True, tag = B"0001"
    )
    val attnVCacheCmd = GenAxiDataMoverCmd.retStream(
      U(mmap.attnVCache_addr),
      GenMemCmdLen.kvCache(dim / head, kvReadToken).asUInt,
      attnHeadBase,
      inc = True, eof = True, tag = B"0001"
    )

    val ports = 5
    val mux = new StreamMux(Fragment(Bits(72 bits)), ports)
    mux.io.inputs(0) << attnQCmdVec
    mux.io.inputs(1) << attnKCmdVec
    mux.io.inputs(2) << attnKCacheCmd
    mux.io.inputs(3) << attnVCmdVec
    mux.io.inputs(4) << attnVCacheCmd
    val sel = UInt(log2Up(ports) bits).setAsReg().init(0)
    val muxOutFire = mux.io.output.fire
    val muxOutIsFirst = mux.io.output.isFirst
    val selOvf = sel === ports - 1
    mux.io.select := sel
    when(muxOutFire & mux.io.output.last) {
      sel := sel + 1
      when(selOvf) {
        sel.clearAll()
      }
    }

    val tagVec = Vec(
      B(param.ATTN_W_Q, 6 bits),
      B(param.ATTN_W_K, 6 bits),
      B(param.ATTN_K_CACHE, 6 bits),
      B(param.ATTN_W_V, 6 bits),
      B(param.ATTN_V_CACHE, 6 bits)
    )
    val tagSel = UInt(log2Up(ports) bits).setAsReg().init(0)
    val tagFire = muxOutFire & muxOutIsFirst
    when(muxOutFire & muxOutIsFirst) {
      tagSel := tagSel + 1
      when(tagSel === ports - 1) {
        tagSel.clearAll()
      }
    }
    val tag = Stream(Bits(6 bits))
    tag.valid := tagFire
    tag.payload := tagVec(tagSel)
    val cmd = Stream(Fragment(Bits(72 bits)))
    cmd.arbitrationFrom(mux.io.output)
    cmd.fragment := mux.io.output.fragment
    cmd.last := mux.io.output.last & selOvf
  }

  val attnQKVWithSz = new Area {
    val attnQCmdVec = util.GenSplitAlignTransfer(mmap.attnQ_addr, mmap.attnQ_len, attnHeadBase, pageSize)
    val attnKCmdVec = util.GenSplitAlignTransfer(mmap.attnK_addr, mmap.attnK_len, attnHeadBase, pageSize)
    val attnVCmdVec = util.GenSplitAlignTransfer(mmap.attnV_addr, mmap.attnV_len, attnHeadBase, pageSize)
    val attnKszCmd = GenAxiDataMoverCmd.retStream(
      U(mmap.attnKScaleZero_addr),
      GenMemCmdLen.kvScaleZero(kvReadToken, busWidth).asUInt,
      attnHeadBase,
      inc = True, eof = True, tag = B"0010"
    )
    val attnVszCmd = GenAxiDataMoverCmd.retStream(
      U(mmap.attnVScaleZero_addr),
      GenMemCmdLen.kvScaleZero(kvReadToken, busWidth).asUInt,
      attnHeadBase,
      inc = True, eof = True, tag = B"0010"
    )
    val attnKCacheCmd = GenAxiDataMoverCmd.retStream(
      U(mmap.attnKCache_addr),
      GenMemCmdLen.kvCache(dim / head, kvReadToken).asUInt,
      attnHeadBase,
      inc = True, eof = True, tag = B"0001"
    )
    val attnVCacheCmd = GenAxiDataMoverCmd.retStream(
      U(mmap.attnVCache_addr),
      GenMemCmdLen.kvCache(dim / head, kvReadToken).asUInt,
      attnHeadBase,
      inc = True, eof = True, tag = B"0001"
    )

    val ports = 7
    val mux = new StreamMux(Fragment(Bits(72 bits)), ports)
    mux.io.inputs(0) << attnQCmdVec
    mux.io.inputs(1) << attnKCmdVec
    mux.io.inputs(2) << attnKszCmd
    mux.io.inputs(3) << attnKCacheCmd
    mux.io.inputs(4) << attnVCmdVec
    mux.io.inputs(5) << attnVszCmd
    mux.io.inputs(6) << attnVCacheCmd
    val sel = UInt(log2Up(ports) bits).setAsReg().init(0)
    val muxOutFire = mux.io.output.fire
    val muxOutIsFirst = mux.io.output.isFirst
    val selOvf = sel === ports - 1
    mux.io.select := sel
    when(muxOutFire & mux.io.output.last) {
      sel := sel + 1
      when(selOvf) {
        sel.clearAll()
      }
    }

    val tagVec = Vec(
      B(param.ATTN_W_Q, 6 bits),
      B(param.ATTN_W_K, 6 bits),
      B(param.ATTN_K_CACHE, 6 bits),
      B(param.ATTN_K_CACHE, 6 bits),
      B(param.ATTN_W_V, 6 bits),
      B(param.ATTN_V_CACHE, 6 bits),
      B(param.ATTN_V_CACHE, 6 bits)
    )
    val tagSel = UInt(log2Up(ports) bits).setAsReg().init(0)
    val tagFire = muxOutFire & muxOutIsFirst
    when(muxOutFire & muxOutIsFirst) {
      tagSel := tagSel + 1
      when(tagSel === ports - 1) {
        tagSel.clearAll()
      }
    }
    val tag = Stream(Bits(6 bits))
    tag.valid := tagFire
    tag.payload := tagVec(tagSel)
    val cmd = Stream(Fragment(Bits(72 bits)))
    cmd.arbitrationFrom(mux.io.output)
    cmd.fragment := mux.io.output.fragment
    cmd.last := mux.io.output.last & selOvf
  }

  val mlpWithPredict = new Area {
    //    val mlpPredUSplit = 1
    //    val mlpLnCmd = GenAxiDataMoverCmd.retStream(U(mmap.mlpLnScale_addr), U(mmap.mlpLnScale_len), layerBase, inc = True, eof = True, tag = B"0000")
    //    val attnOCmdVec = gen_split_cmd_stream(mmap.attnO_addr, mmap.attnO_len, layerBase, attnOSplit)
    //    val mlpPredUCmd = gen_split_cmd_stream(mmap.mlpPredU_addr, mmap.mlpPredU_len, layerBase, mlpPredUSplit)
    //
    //    val ports = 4
    //    val indexCmdType = 4
    //    val tagCnt = ports + indexCmdType - 1
    //    val mux = new StreamMux(Fragment(Bits(72 bits)), ports)
    //    mux.io.inputs(0) << attnOCmdVec
    //    mux.io.inputs(1) << mlpLnCmd
    //    mux.io.inputs(2) << mlpPredUCmd
    //    mux.io.inputs(3) << indexCmdGen.toMlpPredict
    //    val sel = UInt(log2Up(tagCnt) bits).setAsReg().init(0)
    //    val muxOutFire = mux.io.output.fire
    //    val muxOutIsFirst = mux.io.output.isFirst
    //    val selOvf = sel === tagCnt - 1
    //    val muxSel = UInt(log2Up(ports) bits).setAsReg().init(0)
    //    mux.io.select := muxSel
    //    when(muxOutFire & mux.io.output.last) {
    //      sel := sel + 1
    //      when(muxSel =/= ports - 1) {
    //        muxSel := muxSel + 1
    //      }
    //      when(selOvf) {
    //        sel.clearAll()
    //        muxSel.clearAll()
    //      }
    //    }
    //
    //    val tagVec = Vec(
    //      B(param.ATTN_W_O, 6 bits),
    //      B(param.MLP_LN_SCALE, 6 bits),
    //      B(param.MLP_PRED_W_U, 6 bits),
    //      B(param.MLP_PRED_W_D, 6 bits),
    //      B(param.MLP_W_G, 6 bits),
    //      B(param.MLP_W_U, 6 bits),
    //      B(param.MLP_W_D, 6 bits)
    //    )
    //    val tagSel = UInt(log2Up(tagCnt) bits).setAsReg().init(0)
    //    val tagFire = muxOutFire & muxOutIsFirst
    //    when(muxOutFire & muxOutIsFirst) {
    //      tagSel := tagSel + 1
    //      when(tagSel === tagCnt - 1) {
    //        tagSel.clearAll()
    //      }
    //    }
    //    val tag = Stream(Bits(6 bits))
    //    tag.valid := tagFire
    //    tag.payload := tagVec(tagSel)
    //    val cmd = Stream(Fragment(Bits(72 bits)))
    //    cmd.arbitrationFrom(mux.io.output)
    //    cmd.fragment := mux.io.output.fragment
    //    cmd.last := mux.io.output.last & selOvf

    indexCmdGen.toMlpPredict.freeRun()
    val cmd = Stream(Fragment(Bits(72 bits)))
    cmd.valid.clear()
    cmd.payload.clearAll()
    val tag = Stream(Bits(6 bits))
    tag.valid.clear()
    tag.payload.clearAll()
  }

  val mlpDense = new Area {
    val mlpLnCmd = GenAxiDataMoverCmd.retStream(U(mmap.mlpLnScale_addr), U(mmap.mlpLnScale_len), layerBase, inc = True, eof = True, tag = B"0000")
    val attnOCmdVec = util.GenSplitAlignTransfer(mmap.attnO_addr, mmap.attnO_len, layerBase, pageSize)
    val mlpDenseGCmdVec = util.GenSplitAlignTransfer(mmap.mlpDenseG_addr, mmap.mlpDenseG_len, layerBase, pageSize)

    val ports = 4
    val indexCmdType = 2
    val tagCnt = ports + indexCmdType - 1
    val mux = new StreamMux(Fragment(Bits(72 bits)), ports)
    mux.io.inputs(0) << attnOCmdVec
    mux.io.inputs(1) << mlpLnCmd
    mux.io.inputs(2) << mlpDenseGCmdVec
    mux.io.inputs(3) << indexCmdGen.toMlpDense
    val sel = UInt(log2Up(tagCnt) bits).setAsReg().init(0)
    val muxOutFire = mux.io.output.fire
    val muxOutIsFirst = mux.io.output.isFirst
    val selOvf = sel === tagCnt - 1
    val muxSel = UInt(log2Up(ports) bits).setAsReg().init(0)
    mux.io.select := muxSel
    when(muxOutFire & mux.io.output.last) {
      when(muxSel =/= ports - 1) {
        muxSel := muxSel + 1
      }
      sel := sel + 1
      when(selOvf) {
        sel.clearAll()
        muxSel.clearAll()
      }
    }

    val tagVec = Vec(
      B(param.ATTN_W_O, 6 bits),
      B(param.MLP_LN_SCALE, 6 bits),
      B(param.MLP_W_G, 6 bits),
      B(param.MLP_W_U, 6 bits),
      B(param.MLP_W_D, 6 bits)
    )
    val tagSel = UInt(log2Up(tagCnt) bits).setAsReg().init(0)
    val tagFire = muxOutFire & muxOutIsFirst
    when(muxOutFire & muxOutIsFirst) {
      tagSel := tagSel + 1
      when(tagSel === tagCnt - 1) {
        tagSel.clearAll()
      }
    }
    val tag = Stream(Bits(6 bits))
    tag.valid := tagFire
    tag.payload := tagVec(tagSel)
    val cmd = Stream(Fragment(Bits(72 bits)))
    cmd.arbitrationFrom(mux.io.output)
    cmd.fragment := mux.io.output.fragment
    cmd.last := mux.io.output.last & selOvf
  }

  val logits = new Area {

    //    val lgSparseScale = GenAxiDataMoverCmd.retStream(U(mmap.lgSparseLnScale_addr), U(mmap.lgSparseLnScale_len), U(baseAddr), inc = True, eof = True)
    //    val lgSparseHeadVec = gen_split_cmd_stream(mmap.lgSparseHead_addr, mmap.lgSparseHead_len, U(baseAddr), lgSplit)
    val lgDenseScale = GenAxiDataMoverCmd.retStream(U(mmap.lgDenseLnScale_addr), U(mmap.lgDenseLnScale_len), U(baseAddr), inc = True, eof = True)
    val lgDenseHeadVec = util.GenSplitAlignTransfer(mmap.lgDenseHead_addr, mmap.lgDenseHead_len, U(baseAddr), pageSize)

    val ports = 2
    val mux = new StreamMux(Fragment(Bits(72 bits)), ports * 2)
    mux.io.inputs(0) << lgDenseScale
    mux.io.inputs(1) << lgDenseHeadVec
    //    mux.io.inputs(2) << lgSparseScale
    //    mux.io.inputs(3) << lgSparseHeadVec
    mux.io.inputs(2).valid.clear()
    mux.io.inputs(2).payload.clearAll()
    mux.io.inputs(3).valid.clear()
    mux.io.inputs(3).payload.clearAll()

    val sel = UInt(log2Up(ports) bits).setAsReg().init(0)
    val muxOutFire = mux.io.output.fire
    val muxOutIsFirst = mux.io.output.isFirst
    val selOvf = sel === ports - 1
    mux.io.select := (status.enPredictor ## sel).asUInt
    when(muxOutFire & mux.io.output.last) {
      sel := sel + 1
      when(sel === ports - 1) {
        sel.clearAll()
      }
    }

    val tagVec = Vec(B(param.OUT_LN_SCALE, 6 bits), B(param.LM_HEAD_W, 6 bits))
    val tagSel = UInt(log2Up(ports) bits).setAsReg().init(0)
    val tagFire = muxOutFire & muxOutIsFirst
    when(muxOutFire & muxOutIsFirst) {
      tagSel := tagSel + 1
      when(tagSel === ports - 1) {
        tagSel.clearAll()
      }
    }
    val tag = Stream(Bits(6 bits))
    tag.valid := tagFire
    tag.payload := tagVec(tagSel)
    val cmd = Stream(Fragment(Bits(72 bits)))
    cmd.arbitrationFrom(mux.io.output)
    cmd.fragment := mux.io.output.fragment
    cmd.last := mux.io.output.last & selOvf
  }

  val kvDone = attnKV.cmd.fire & attnKV.cmd.last
  val qkvNoSzDone = attnQKVNoSz.cmd.fire & attnQKVNoSz.cmd.last
  val qkvDone = attnQKVWithSz.cmd.fire & attnQKVWithSz.cmd.last
  val sparseMlpDone = mlpWithPredict.cmd.fire & mlpWithPredict.cmd.last
  val denseMlpDone = mlpDense.cmd.fire & mlpDense.cmd.last
  val mlpDone = sparseMlpDone || denseMlpDone
  val logitsDone = logits.cmd.fire & logits.cmd.last

  val mm2sCmdMux = new StreamMux(Fragment(Bits(72 bits)), 8)
  mm2sCmdMux.io.inputs(0) << tokenIn
  mm2sCmdMux.io.inputs(1) << attnLn
  mm2sCmdMux.io.inputs(2) << attnKV.cmd
  mm2sCmdMux.io.inputs(3) << attnQKVNoSz.cmd
  mm2sCmdMux.io.inputs(4) << attnQKVWithSz.cmd
  mm2sCmdMux.io.inputs(5) << mlpWithPredict.cmd
  mm2sCmdMux.io.inputs(6) << mlpDense.cmd
  mm2sCmdMux.io.inputs(7) << logits.cmd
  val mm2sCmdMuxOut = mm2sCmdMux.io.output

  val busTagMux = new StreamMux(Bits(6 bits), 8)
  busTagMux.io.inputs(0) << tokenTag
  busTagMux.io.inputs(1) << attnLnTag
  busTagMux.io.inputs(2) << attnKV.tag
  busTagMux.io.inputs(3) << attnQKVNoSz.tag
  busTagMux.io.inputs(4) << attnQKVWithSz.tag
  busTagMux.io.inputs(5) << mlpWithPredict.tag
  busTagMux.io.inputs(6) << mlpDense.tag
  busTagMux.io.inputs(7) << logits.tag
  val busTagMuxOut = busTagMux.io.output

  val tagFifo = new StreamFifo(Bits(6 bits), 64, forFMax = true)
  tagFifo.io.push.arbitrationFrom(busTagMuxOut)
  tagFifo.io.push.payload := busTagMuxOut.payload

  local.bus.arbitrationFrom(io.mm2s)
  local.bus.data := io.mm2s.data
  local.bus.dest := tagFifo.io.pop.payload.take(6).asUInt
  local.bus.last := io.mm2s.last
  tagFifo.io.pop.ready := io.mm2s.fire & io.mm2s.last

  val prefill = Bool().setAsReg().init(True)
  when(tokenTag.fire & tokenTag.payload === extTokenTag._2) {
    prefill.clear()
  }

  io.s2mm.arbitrationFrom(local.kvBus)
  io.s2mm.data := local.kvBus.data
  io.s2mm.last := local.kvBus.last
  io.s2mm.keep.setAll()

  val mm2sCmd = Stream(Bits(72 bits))
  mm2sCmd.arbitrationFrom(mm2sCmdMuxOut)
  mm2sCmd.payload := mm2sCmdMuxOut.fragment

  io.mm2sCmd << mm2sCmd.s2mPipe().m2sPipe()

  // Count only accepted data beats belonging to this projection. The bound is
  // deliberately K-independent: one target-weight stream serves every
  // candidate row in GEMM mode.
  val descriptorStep = local.bus.fire &&
    local.bus.dest === descriptorProjectionTag.asUInt
  val perfCounters = new util.SpeculativePerfCounters(busWidth / 8)
  perfCounters.io.clear := status.perfWindowClear
  perfCounters.io.active := status.perfWindowActive
  perfCounters.io.weightBeat := descriptorStep
  perfCounters.io.kvReadBeat := status.perfKvReadBeat
  perfCounters.io.kvWriteBeat := io.s2mm.fire
  perfCounters.io.memoryStall := local.bus.valid && !local.bus.ready
  perfCounters.io.resultStall := False
  when(descriptorActive && descriptorStep) {
    when(descriptorBeatCnt === descriptorBeatsPerRow - 1) {
      descriptorBeatCnt.clearAll()
      when(descriptorRowCnt === descriptorRows - 1) {
        descriptorActive.clear()
        projectionDone.set()
      } otherwise {
        descriptorRowCnt := descriptorRowCnt + 1
      }
    } otherwise {
      descriptorBeatCnt := descriptorBeatCnt + 1
    }
  }

  enIncHead := kvDone || qkvNoSzDone || qkvDone
  enIncLayer := mlpDone
  when(prefill & layerCntOvf) {
    enIncLayer := kvDone & headCntOvf
  }

  val enTokenCnt = enIncLayer & layerCntOvf
  // The descriptor owns the in-flight speculative batch.  Keeping this
  // guard on the legacy counter is what makes candidate work invisible to
  // the committed token position; when no descriptor is valid this is the
  // original increment condition.
  when(enTokenCnt && !descriptorActive && !io.speculativeBatch.fire) {
    token := token + 1
  }

  val select = UInt(3 bits).setAsReg().init(0)
  val selectNext = UInt(3 bits)
  select.addAttribute("max_fanout", 100)
  selectNext := select
  select := selectNext
  mm2sCmdMux.io.select := select
  busTagMux.io.select := select

  when(select === 0 & tokenIn.fire) {
    selectNext := 1
  }
  when(select === 1 & attnLn.fire) {
    when(firstToken || prefill & layerCntOvf) {
      selectNext := 2
    }.elsewhen(noSzFromMem) {
      selectNext := 3
    }.otherwise {
      selectNext := 4
    }
  }
  when(select === 2 & kvDone & headCntOvf) {
    when(layerCntOvf) {
      selectNext := 0
    }.otherwise {
      when(status.enPredictor) {
        selectNext := 5
      }.otherwise {
        selectNext := 6
      }
    }
  }
  when(select === 3 & qkvNoSzDone & headCntOvf) {
    when(status.enPredictor) {
      selectNext := 5
    }.otherwise {
      selectNext := 6
    }
  }
  when(select === 4 & qkvDone & headCntOvf) {
    when(status.enPredictor) {
      selectNext := 5
    }.otherwise {
      selectNext := 6
    }
  }
  when(select === 5 & sparseMlpDone) {
    when(layerCntOvf) {
      selectNext := 7
    }.otherwise {
      selectNext := 1
    }
  }
  when(select === 6 & denseMlpDone) {
    when(layerCntOvf) {
      selectNext := 7
    }.otherwise {
      selectNext := 1
    }
  }
  when(select === 7 & logitsDone) {
    selectNext := 0
  }


  val s2mm = new Area {

    val s2mmTokenCnt = UInt(log2Up(maxToken) bits).setAsReg().init(0)
    val kvWritePosition = new SpeculativeKvPosition(maxToken)
    kvWritePosition.io.legacyPosition := s2mmTokenCnt
    kvWritePosition.io.speculativeEnable := status.speculativeEnable
    kvWritePosition.io.committedPosition := status.speculativeCommitted
    kvWritePosition.io.candidatePosition := status.speculativeQuery
    // Capture the physical KV slot with the launch. This keeps the address
    // stable even if PS changes q immediately after enqueueing the next job.
    val tokenEnFifo = new StreamFifo(UInt(log2Up(maxToken) bits), 64, forFMax = true)
    tokenEnFifo.io.push.valid := io.tokenIndex.fire
    tokenEnFifo.io.push.payload := kvWritePosition.io.selectedPosition
    tokenEnFifo.io.pop.ready.clear()

    val mallocPerHead = U(mmap.attnQKV_len)
    val headBase = UInt(32 bits).setAsReg().init(mmap.attnQKV_addr)
    val headBaseNext = UInt(32 bits)
    val enIncHead = Bool()
    val headCnt = UInt(log2Up(head / numOfCore) bits).setAsReg().init(0)
    val headCntAbout2Ovf = headCnt === head / numOfCore - 2
    val headCntOvf = Bool().setAsReg().init(False)
    headBase := headBaseNext
    headBaseNext := headBase
    when(enIncHead) {
      headCnt := headCnt + 1
      headBaseNext := headBase + mallocPerHead
      when(headCntAbout2Ovf)(headCntOvf.set())
      when(headCntOvf) {
        headCnt := 0
        headBaseNext := mmap.attnQKV_addr
        headCntOvf.clear()
      }
    }

    val mallocPerLayer = Mux(status.enPredictor, U(mmap.sparseLayer_len), U(mmap.denseLayer_len))
    val layerBase = UInt(32 bits).setAsReg().init(baseAddr + mmap.afterTokenizer_addr)
    val layerBaseNext = UInt(32 bits)
    val enIncLayer = Bool()
    val layerCnt = UInt(log2Up(layer) bits).setAsReg().init(0)
    val layerCntAbout2Ovf = layerCnt === layer - 2
    val layerCntOvf = Bool().setAsReg().init(False)
    layerBase := layerBaseNext
    layerBaseNext := layerBase
    when(enIncLayer) {
      layerCnt := layerCnt + 1
      layerBaseNext := layerBase + mallocPerLayer
      when(layerCntAbout2Ovf)(layerCntOvf.set())
      when(layerCntOvf) {
        layerCnt := 0
        layerBaseNext := baseAddr + mmap.afterTokenizer_addr
        layerCntOvf.clear()
        when(!descriptorActive && !io.speculativeBatch.fire) {
          s2mmTokenCnt := s2mmTokenCnt + 1
        }
        tokenEnFifo.io.pop.ready.set()
      }
    }

    // Speculative candidate writes must not advance the legacy physical
    // counter. Continuously mirroring committed state also makes fallback to
    // target-only mode resume at the accepted pointer.
    when(status.speculativeEnable) {
      s2mmTokenCnt := status.speculativeCommitted
    }

    // Keep the physical write counter frozen while a production descriptor
    // is in flight.  The selected token is still captured in tokenEnFifo,
    // so the existing candidate address path remains deterministic.
    //    layerCnt.addAttribute("mark_debug", "true")
    //    s2mmTokenCnt.addAttribute("mark_debug", "true")

    val attnHeadBase = UInt(32 bits).setAsReg().init(0)
    val attnHeadBaseNext = layerBaseNext + headBaseNext
    attnHeadBase := attnHeadBaseNext

    val s2mmWriteToken = tokenEnFifo.io.pop.payload
    val s2mmTokenCntLow = s2mmWriteToken.takeLow(log2Up(busWidth / 32))
    val s2mmTokenHigh = s2mmWriteToken.dropLow(log2Up(busWidth / 32)).asUInt
    val s2mSzToMem = s2mmTokenCntLow.andR

    val kvSzLen = busWidth / 8
    val kvLen = dim / head
    val kCacheCmd = GenAxiDataMoverCmd(
      (s2mmWriteToken ## B(0, log2Up(kvLen / dmaSplit) bits)).asUInt,
      U(kvLen),
      attnHeadBase + mmap.attnKCache_addr,
      inc = True, eof = True, tag = B"0001"
    )
    val vCacheCmd = GenAxiDataMoverCmd(
      (s2mmWriteToken ## B(0, log2Up(kvLen / dmaSplit) bits)).asUInt,
      U(kvLen),
      attnHeadBase + mmap.attnVCache_addr,
      inc = True, eof = True, tag = B"0001"
    )
    val kSzCmd = GenAxiDataMoverCmd(
      (s2mmTokenHigh ## B(0, log2Up(kvSzLen / dmaSplit) bits)).asUInt,
      U(kvSzLen),
      attnHeadBase + mmap.attnKScaleZero_addr,
      inc = True, eof = True, tag = B"0010"
    )
    val vSzCmd = GenAxiDataMoverCmd(
      (s2mmTokenHigh ## B(0, log2Up(kvSzLen / dmaSplit) bits)).asUInt,
      U(kvSzLen),
      attnHeadBase + mmap.attnVScaleZero_addr,
      inc = True, eof = True, tag = B"0010"
    )

    val s2mmCmd = Stream(Bits(72 bits))
    val vec = Vec(kSzCmd, kCacheCmd, vSzCmd, vCacheCmd)
    val toMemSel = UInt(2 bits)
    s2mmCmd.payload := vec(toMemSel)
    s2mmCmd.valid := tokenEnFifo.io.pop.valid

    val cnt = UInt(2 bits).setAsReg().init(0)
    when(s2mmCmd.fire) {
      cnt := cnt + 1
    }

    enIncHead := s2mmCmd.fire & cnt.andR
    enIncLayer := enIncHead & headCntOvf

    toMemSel := cnt
    val s2mmCmdThrow = s2mmCmd.throwWhen(~s2mSzToMem & cnt(0) === False)

    val cmdFifo = new StreamFifo(Bits(72 bits), 32, forFMax = true)
    cmdFifo.io.push << s2mmCmdThrow
    io.s2mmCmd << cmdFifo.io.pop
  }

  status.projectionDone := projectionDone
  status.projectionError := projectionError
  status.projectionDoneTag := descriptorProjectionTag
  status.projectionDoneLayer := descriptorLayerId
  status.descriptorActive := descriptorActive
  status.perfWeightBytes := perfCounters.io.weightBytes
  status.perfKvReadBytes := perfCounters.io.kvReadBytes
  status.perfKvWriteBytes := perfCounters.io.kvWriteBytes
  status.perfVerifyCycles := perfCounters.io.verifyCycles
  status.perfMemoryStallCycles := perfCounters.io.memoryStallCycles
}
