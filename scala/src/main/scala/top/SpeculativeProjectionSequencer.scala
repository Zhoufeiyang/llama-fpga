package top

import spinal.core._
import spinal.lib._

import scala.language.postfixOps

/** Identity attached to a production target-weight projection completion. */
case class SpeculativeProjectionCompletion() extends Bundle {
  val projectionTag = Bits(6 bits)
  val layerId = UInt(8 bits)
}

/**
  * Transformer-level sequencing contract for a speculative target pass.
  *
  * A single launch is expanded into the weight projections used by one or
  * more target layers:
  *
  *   Q -> K -> V -> attention -> O -> G -> U -> MLP -> D -> (next layer)
  *   -> LM head
  *
  * The output is a one-entry-at-a-time descriptor stream.  `k` is carried
  * unchanged to every descriptor, but never enters rows/beatsPerRow; the
  * latter are physical target-weight geometry and therefore produce exactly
  * one DMA pass for each projection independent of K.  Projection and
  * barrier completion inputs are deliberately explicit: this block does not
  * infer arithmetic completion from a data-valid pulse.
  *
  * The component is a production control-plane block, not a second MAC
  * engine.  It is parameterized for the full Llama2 geometry and accepts
  * smaller dimensions for focused simulation.
  */
class SpeculativeProjectionSequencer(
    totalLayers: Int = 32,
    qkvRows: Int = 4096,
    qkvBeats: Int = 32,
    mlpRows: Int = 11008,
    mlpBeats: Int = 32,
    dRows: Int = 4096,
    dBeats: Int = 86,
    lmRows: Int = 32000,
    lmBeats: Int = 32
) extends Component {
  require(totalLayers >= 1 && totalLayers <= 255)
  require(qkvRows > 0 && qkvBeats > 0)
  require(mlpRows > 0 && mlpBeats > 0)
  require(dRows > 0 && dBeats > 0)
  require(lmRows > 0 && lmBeats > 0)
  require(qkvRows <= 65535 && qkvBeats <= 65535)
  require(mlpRows <= 65535 && mlpBeats <= 65535)
  require(dRows <= 65535 && dBeats <= 65535)
  require(lmRows <= 65535 && lmBeats <= 65535)

  val io = new Bundle {
    val start = slave(Stream(util.SpeculativeBatchDescriptor()))
    val projection = master(Stream(util.SpeculativeBatchDescriptor()))
    val projectionDone = slave(Flow(SpeculativeProjectionCompletion()))
    val attentionRequest = out Bool()
    val attentionDone = in Bool()
    val mlpActivationRequest = out Bool()
    val mlpActivationDone = in Bool()
    val abort = in Bool()
    val busy = out Bool()
    val done = out Bool()
    val error = out Bool()
    val errorCode = out Bits(4 bits)
    val launchedProjections = out UInt(32 bits)
    // Physical packed-weight beats requested by the descriptors.  Since rows
    // and beatsPerRow are K-independent, this is also a direct control-plane
    // witness that the scheduler did not duplicate a DMA pass for candidates.
    val launchedWeightBeats = out UInt(32 bits)
    val sequenceK = out UInt(3 bits)
    val sequenceLayer = out UInt(8 bits)
  }

  noIoPrefix()

  // Weight-stream tags from cfgGen.LLaMA2_7B.param, rather than tensor tags.
  // These are the tags observed on the production GenMemCmdLenAlign stream.
  val tagQ = B(4, 6 bits)
  val tagK = B(5, 6 bits)
  val tagV = B(7, 6 bits)
  val tagO = B(11, 6 bits)
  val tagG = B(15, 6 bits)
  val tagU = B(16, 6 bits)
  val tagD = B(17, 6 bits)
  val tagLm = B(19, 6 bits)

  val idle = U(0, 4 bits)
  val issueQ = U(1, 4 bits)
  val issueK = U(2, 4 bits)
  val issueV = U(3, 4 bits)
  val waitAttention = U(4, 4 bits)
  val issueO = U(5, 4 bits)
  val issueG = U(6, 4 bits)
  val issueU = U(7, 4 bits)
  val waitMlp = U(8, 4 bits)
  val issueD = U(9, 4 bits)
  val issueLm = U(10, 4 bits)
  val complete = U(11, 4 bits)
  val fault = U(12, 4 bits)

  val state = Reg(UInt(4 bits)) init idle
  val modeReg = Reg(Bool()) init False
  val kReg = Reg(UInt(3 bits)) init 1
  val firstLayerReg = Reg(UInt(8 bits)) init 0
  val layerReg = Reg(UInt(8 bits)) init 0
  val inflight = Reg(Bool()) init False
  val errorReg = Reg(Bool()) init False
  val errorCodeReg = Reg(Bits(4 bits)) init B(0, 4 bits)
  val launchedReg = Reg(UInt(32 bits)) init 0
  val launchedWeightBeatsReg = Reg(UInt(32 bits)) init 0

  val issueState = state === issueQ || state === issueK || state === issueV ||
    state === issueO || state === issueG || state === issueU ||
    state === issueD || state === issueLm
  val completionExpectedTag = Bits(6 bits)
  val completionRows = UInt(16 bits)
  val completionBeats = UInt(16 bits)
  completionExpectedTag := 0
  completionRows := 0
  completionBeats := 0
  switch(state) {
    is(issueQ) { completionExpectedTag := tagQ; completionRows := qkvRows; completionBeats := qkvBeats }
    is(issueK) { completionExpectedTag := tagK; completionRows := qkvRows; completionBeats := qkvBeats }
    is(issueV) { completionExpectedTag := tagV; completionRows := qkvRows; completionBeats := qkvBeats }
    is(issueO) { completionExpectedTag := tagO; completionRows := qkvRows; completionBeats := qkvBeats }
    is(issueG) { completionExpectedTag := tagG; completionRows := mlpRows; completionBeats := mlpBeats }
    is(issueU) { completionExpectedTag := tagU; completionRows := mlpRows; completionBeats := mlpBeats }
    is(issueD) { completionExpectedTag := tagD; completionRows := dRows; completionBeats := dBeats }
    is(issueLm) { completionExpectedTag := tagLm; completionRows := lmRows; completionBeats := lmBeats }
  }

  io.start.ready := state === idle
  io.projection.valid := issueState && !inflight
  io.projection.payload.mode := modeReg
  io.projection.payload.k := kReg
  io.projection.payload.projectionTag := completionExpectedTag
  io.projection.payload.layerId := layerReg
  io.projection.payload.rows := completionRows
  io.projection.payload.beatsPerRow := completionBeats

  io.attentionRequest := state === waitAttention
  io.mlpActivationRequest := state === waitMlp
  io.busy := state =/= idle && state =/= complete && state =/= fault
  io.done := state === complete
  io.error := errorReg || state === fault
  io.errorCode := errorCodeReg
  io.launchedProjections := launchedReg
  io.launchedWeightBeats := launchedWeightBeatsReg
  io.sequenceK := kReg
  io.sequenceLayer := layerReg

  val startKValid = io.start.payload.k >= 1 && io.start.payload.k <= 4
  val startLayerValid = io.start.payload.layerId < totalLayers
  val startModeValid = io.start.payload.mode || io.start.payload.k === 1
  val projectionFire = io.projection.fire
  val completionSeen = io.projectionDone.valid && inflight
  val completionMatches = io.projectionDone.payload.projectionTag === completionExpectedTag &&
    io.projectionDone.payload.layerId === layerReg
  val finalLayer = layerReg === totalLayers - 1

  // The state transition is intentionally completion-driven.  In particular,
  // accepting a descriptor does not advance to the next matrix; the target
  // datapath must return the same tag/layer identity first.
  when(state === idle && io.start.fire) {
    when(!startKValid) {
      state := fault
      errorReg := True
      errorCodeReg := B(1, 4 bits) // K outside 1..4
    } elsewhen (!startLayerValid) {
      state := fault
      errorReg := True
      errorCodeReg := B(2, 4 bits) // first layer outside configured range
    } elsewhen (!startModeValid) {
      state := fault
      errorReg := True
      errorCodeReg := B(3, 4 bits) // legacy GEMV requires K=1
    } otherwise {
      modeReg := io.start.payload.mode
      kReg := io.start.payload.k
      firstLayerReg := io.start.payload.layerId
      layerReg := io.start.payload.layerId
      inflight.clear()
      launchedReg.clearAll()
      launchedWeightBeatsReg.clearAll()
      errorReg.clear()
      errorCodeReg.clearAll()
      state := issueQ
    }
  }

  when(projectionFire) {
    inflight.set()
    launchedReg := launchedReg + 1
    launchedWeightBeatsReg := launchedWeightBeatsReg +
      (completionRows.resize(32) * completionBeats.resize(32)).resize(32)
  }

  when(completionSeen) {
    when(!completionMatches) {
      state := fault
      errorReg := True
      errorCodeReg := B(4, 4 bits) // completion identity mismatch
      inflight.clear()
    } otherwise {
      inflight.clear()
      switch(state) {
        is(issueQ) { state := issueK }
        is(issueK) { state := issueV }
        is(issueV) { state := waitAttention }
        is(issueO) { state := issueG }
        is(issueG) { state := issueU }
        is(issueU) { state := waitMlp }
        is(issueD) {
          when(finalLayer) {
            state := issueLm
          } otherwise {
            layerReg := layerReg + 1
            state := issueQ
          }
        }
        is(issueLm) { state := complete }
      }
    }
  }

  when(state === waitAttention && io.attentionDone) {
    state := issueO
  }
  when(state === waitMlp && io.mlpActivationDone) {
    state := issueD
  }
  when(state === complete) {
    // done is a one-cycle pulse; a new start can be accepted on the next
    // cycle, while FAULT remains sticky until reset.
    state := idle
  }

  // Rollback/timeout terminates the descriptor epoch immediately. Any late
  // completion is ignored in IDLE and cannot retire the next transaction.
  when(io.abort) {
    state := idle
    inflight.clear()
    errorReg.clear()
    errorCodeReg.clearAll()
    launchedReg.clearAll()
    launchedWeightBeatsReg.clearAll()
  }
}

object SpeculativeProjectionSequencerTest extends App {
  SpinalConfig(
    targetDirectory = "../kv260/speculative/build/p3_production_sequence_elab",
    oneFilePerComponent = false
  ).generateVerilog(new SpeculativeProjectionSequencer(
    totalLayers = 2,
    qkvRows = 4,
    qkvBeats = 2,
    mlpRows = 6,
    mlpBeats = 3,
    dRows = 4,
    dBeats = 3,
    lmRows = 8,
    lmBeats = 2
  ))
}
