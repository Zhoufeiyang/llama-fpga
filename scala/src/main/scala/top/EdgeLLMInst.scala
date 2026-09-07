package top

import spinal.core._

import scala.language.postfixOps

object EdgeLLMInst extends App {

  import cfgGen.LLaMA2_7B._
  import EdgeLLMKv260Config._

  val busWidth = 512
  val sgSplit = 4
  val bankLen = busWidth / 4
  val clock = new ClockDomainConfig(resetActiveLevel = LOW)
  val cfg = SpinalConfig(
    defaultConfigForClockDomains = clock,
    inlineRom = true,
    nameWhenByFile = false,
    anonymSignalPrefix = "t"
  )
  cfg.generateVerilog(
    new DataPath_xN(
      sync = sync,
      numOfCore = numOfCore,
      DMA_SPLIT = DMA_SPLIT,
      baseAddr = baseAddr,
      cmdAddrWidth = cmdAddrWidth,
      splitBaseAddr = splitBaseAddr,
      busWidth = busWidth,
      sgSplit = sgSplit,

      dim = modelCfg.dim,
      head = modelCfg.head,
      headDim = modelCfg.headDim,
      predDim = modelCfg.predDim,
      mlpDim = modelCfg.mlpDim,
      layer = modelCfg.layer,
      maxToken = 1024,
      ropePoint = 1 << 14,
      sqrtHeadDim = modelCfg.sqrtHeadDim,
      vocabSize = modelCfg.vocabSize,

      ropeTagMap = tagMap.ropeTagMap,
      quantTagMap = tagMap.quantTagMap,
      softmaxTag = tagMap.softmaxTag,
      lnInTagMap = tagMap.lnInTagMap,
      zeroFilterTagMap = tagMap.zeroFilterTagMap,

      logitsTag = tag.logitsTag,
      resAddTag = tag.resAddTag,
      normFilterTag = tag.normFilterTag,
      resOut2NodeTag = tag.resOut2NodeTag,
      p2sOut2NodeTag = tag.p2sOut2NodeTag,
      index2NodeTag = tag.index2NodeTag,
      index2UgTag = tag.index2UgTag,

      attnLnTag = tensor.ATTN_LN_OUT,
      logitsLnTag = tensor.LOGITS_LN_OUT,
      lmHeadParamTag = param.LM_HEAD_W,
      attnVParamTag = param.ATTN_W_V,
      mlpDParamTag = param.MLP_W_D,
      kvInTag = tag.kvInTag,

      lnScaleBusTag = tag.axiLnBusTag,
      denseBusTag = tag.axiDenseBusTag,
      kvCacheBusTag = tag.axiKvBusTag,
      sparseDotTagMap = tag.axiSparseDotBusTagMap(busWidth, numOfCore, modelCfg.dim),
      sparseAxpyTagMap = tag.axiSparseAxpyBusTagMap(busWidth, numOfCore, modelCfg.dim, modelCfg.mlpDim),
      denseCfgTag = tag.axiDenseCfgTag,
      sparseCfgTag = tag.axiSparseCfgTag,
      lnSqrCfgTag = tag.axiLnCfgTag,
      kvCfgTag = tag.axiKvCfgTag,

      qkMulTag = tag.qkMulTag,
      ugMulTag = tag.ugMulTag,
      axpyTensorInTag = tag.axpyTensorInTag,
      axpyParamInTag = tag.axpyParamInTag,
      tokenTag = tag.extTokenTag,
      vTensorTag = tag.vTag,

      zeroFilterTag2SeqLen = List(
        (tensor.MLP_PRED_U_FILTER, modelCfg.predDim),
        (tensor.MLP_PRED_D_FILTER, modelCfg.mlpDim / numOfCore),
        (tensor.MLP_G_FILTER, modelCfg.mlpDim)
      ),

      vecIn2ResTag = tag.vecIn2ResTag,
      vecIn2ScalarTag = tag.vecIn2ScalarTag,
      vLocalTag = tag.vLocalTag,
      serial2VecOutTag = tag.serial2VecOutTag,
      busIn2VecOutTag = tag.busIn2VecOutTag,
      engine2VecOutTag = tag.engine2VecOutTag,
      lnOut2VecTag = tag.lnOut2VecTag,
      dotOut2VecTag = tag.dotOut2VecTag,
      rope2VecTag = tag.rope2VecTag,
      dotOut2NodeTag = tag.dotOut2NodeTag,
      cfgInsertTag = tag.cfgInsertTag,
      mlpGTag = tag.axiMlpGTag,

      mul_func = util.fp16mul6.mul,
      add_func = util.fp16add6.add,
      sub_func = util.fp16sub8.sub,
      div_func = util.fp16div12.div,
      acc_func = util.fp16acc16.acc,
      act_func = x => x,
      mul_func_block = util.fp16mul7s.mul,

      rope_highPcs_mul_func = util.fp32mul8.mul,
      rope_toInt_func = util.fp32toint32d6.to,
      rope_fromInt_func = util.fp32int16d4.from,
      quant_conv_func = util.fp16toint9d4.to,
      deQuant_int4_conv_func = util.fp16int5d4.from,
      deQuant_int8_conv_func = util.fp16int9d4.from,

      add_latency = util.fp16add6.latency,
      deQuant_latency = 4 + 1,
      mul_latency = util.fp16mul6.latency,
      acc_latency = util.fp16acc16.latency,
      exp_latency = util.fp16ex12.latency,
      div_latency = util.fp16div12.latency,

      expo_func = util.fp16ex12.exp,
      rsqrt_func = util.fp16rsqrt4.rsqrt,
      lt_func = util.fp16lt0.lt_async,

      fp16toFp32_func = util.fp16toFp32.to,
      fp16toFp32_func_block = util.fp16toFp32s.to,
      fp32toFp16_func = util.fp32toFp16.to,
      fp32mul_func = util.fp32mul8.mul,
      fp32mul_func_block = util.fp32mul8s.mul,
      fp32acc_func = util.fp32acc22.acc,
      fp32rsqrt_func = util.fp32rsqrt32.rsqrt,
      fp32add_func = util.fp32add11.add,
      fp32exp_func = util.fp32exp20.exp,
      fp32div_func = util.fp32div28.div,
      fp32lt_func = util.fp32lt0.lt_async,
      fp32sub_func = util.fp32sub11.sub,
      mix_mul_func = util.fp16mulFp32.apply,
      add_conv_func = util.fp32add2Fp16.apply,

      toFp32_latency = util.fp16toFp32.latency,
      toFp16_latency = util.fp32toFp16.latency,
      fp32Mul_latency = util.fp32mul8.latency,
      fp32Add_latency = util.fp32add11.latency,
      fp32Acc_latency = util.fp32acc22.latency,
      fp32Exp_latency = util.fp32exp20.latency,
      fp32Div_latency = util.fp32div28.latency,

      dotMaxFirstDim = 32,
      axpyMaxFirstDim = 32,
      wkvOutFifoDepth = 32,
      vecP2sFifoDepth = 32,
      vecOutFifoDepth = scala.math.max(64, 128 / numOfCore),
      resetLowPolarity = resetLowPolarity
    )
  )
}
