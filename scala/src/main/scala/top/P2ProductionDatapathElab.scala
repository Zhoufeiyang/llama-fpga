package top

import spinal.core._

/**
  * Small production-datapath elaboration used by the P2 focused gate.  It
  * instantiates the real MulAddSGNew shared multiplier/reduction/FP32 path,
  * with K-capable activation storage, rather than a scheduler sidecar.
  */
object P2ProductionDatapathElab extends App {
  SpinalConfig(
    targetDirectory = "../kv260/speculative/build/p2_production_datapath_elab",
    oneFilePerComponent = false
  ).generateVerilog(new MulAddSGNew(
    numOfCore = 1,
    split = 1,
    width = 16,
    bankLen = 8,
    dotMaxFirstDim = 4,
    axpyMaxFirstDim = 4,
    mul_latency = util.fp16mul6.latency,
    add_latency = util.fp16add6.latency,
    acc_latency = util.fp16acc16.latency,
    add_func = util.fp16add6.add,
    acc_func = util.fp16acc16.acc,
    mul_func_nonblock = util.fp16mul6.mul,
    mul_func_block = util.fp16mul7s.mul,
    toFp32_func = util.fp16toFp32.to,
    toFp32_func_block = util.fp16toFp32s.to,
    toFp16_func = util.fp32toFp16.to,
    fp32Mul_func = util.fp32mul8.mul,
    fp32Add_func = util.fp32add11.add,
    fp32Acc_func = util.fp32acc22.acc,
    toFp32_latency = util.fp16toFp32.latency,
    toFp16_latency = util.fp32toFp16.latency,
    fp32Mul_latency = util.fp32mul8.latency,
    fp32Add_latency = util.fp32add11.latency,
    fp32Acc_latency = util.fp32acc22.latency
  ))
}

/** MulAddEngineNew-only view used to check scalarOut/last boundaries. */
object P2ProductionEngineElab extends App {
  SpinalConfig(
    targetDirectory = "../kv260/speculative/build/p2_production_engine_elab",
    oneFilePerComponent = false
  ).generateVerilog(new MulAddEngineNew(
    width = 16,
    bankLen = 8,
    dotMaxFirstDim = 4,
    axpyMaxFirstDim = 4,
    mul_latency = util.fp16mul6.latency,
    add_latency = util.fp16add6.latency,
    acc_latency = util.fp16acc16.latency,
    add_func = util.fp16add6.add,
    acc_func = util.fp16acc16.acc,
    mul_func_nonblock = util.fp16mul6.mul,
    mul_func_block = util.fp16mul7s.mul,
    speculativeMaxK = 4
  ))
}
