package top

import spinal.core._
import spinal.lib._

import scala.language.postfixOps

/**
  * Production-side operand front-end for the shared MulAdd datapath.
  *
  * The legacy engine consumes one activation tile and one row-major weight
  * stream.  In GEMM mode this block presents the same engine with a
  * token-major activation tile (K * B beats) and replays each buffered weight
  * row K times.  The weight source is therefore accepted once, while the
  * existing multiplier array sees K logical operands.  The row buffer is
  * deliberately only B beats deep; it is not a second copy of the matrix.
  *
  * This block does not assert projection-done.  `replayDone` means only that
  * the final replay beat has left this front-end; the downstream reduction and
  * accumulation pipeline may still be draining.
  */
class SpeculativeGemmReplay(
                            width: Int,
                            bankLen: Int,
                            maxFirstDim: Int,
                            maxK: Int = 4
                          ) extends Component {

  require(isPow2(maxK))
  require(maxK >= 1 && maxK <= 4)
  require(maxFirstDim >= 1)

  val parallelBit = width * bankLen
  val activationDepth = maxFirstDim * maxK
  val countWidth = log2Up(activationDepth + 1)
  // A B-deep RAM has addresses 0..B-1.  The counter therefore needs
  // ceil(log2(B)) bits (with a one-bit floor for B=1), not the width needed
  // to encode B itself.
  val beatWidth = if (maxFirstDim <= 1) 1 else log2Up(maxFirstDim)

  val io = new Bundle {
    val mode = in Bool()                 // latched by DataPath per descriptor
    val k = in UInt(3 bits)

    val wkvIn = slave(Stream(Bits(parallelBit bits)))
    val dotIn = slave(Stream(Bits(parallelBit bits)))
    // Dense post-scales arrive one per physical weight beat.  In GEMM mode
    // they are retained with the weight row and replayed independently of
    // the weight stream's downstream latency.
    val postScaleIn = slave(Stream(Bits(32 bits)))
    val cfgIn = slave(Stream(Bits(32 bits)))

    val wkvOut = master(Stream(Bits(parallelBit bits)))
    val dotOut = master(Stream(Bits(parallelBit bits)))
    val postScaleOut = master(Stream(Bits(32 bits)))
    val cfgOut = master(Stream(Bits(32 bits)))

    val inputWeightBeats = out UInt(32 bits)
    val logicalOperandReplays = out UInt(32 bits)
    val active = out Bool()
    val replayDone = out Bool()
    val error = out Bool()
    val errorCode = out Bits(4 bits)
  }

  noIoPrefix()
  util.AxiStreamSpecRenamer(io.wkvIn)
  util.AxiStreamSpecRenamer(io.dotIn)
  util.AxiStreamSpecRenamer(io.postScaleIn)
  util.AxiStreamSpecRenamer(io.cfgIn)
  util.AxiStreamSpecRenamer(io.wkvOut)
  util.AxiStreamSpecRenamer(io.dotOut)
  util.AxiStreamSpecRenamer(io.postScaleOut)
  util.AxiStreamSpecRenamer(io.cfgOut)

  val idle = U(0, 3 bits)
  val loadActivation = U(1, 3 bits)
  val loadRow = U(2, 3 bits)
  val replayWeights = U(3, 3 bits)
  val doneState = U(4, 3 bits)
  val errorState = U(5, 3 bits)
  val state = Reg(UInt(3 bits)) init idle

  val kReg = UInt(3 bits).setAsReg().init(1)
  val beatsReg = UInt(log2Up(maxFirstDim + 1) bits).setAsReg().init(1)
  val rowsReg = UInt(16 bits).setAsReg().init(1)
  val activationCount = UInt(countWidth bits).setAsReg().init(0)
  val weightCount = UInt(beatWidth bits).setAsReg().init(0)
  val replayBeat = UInt(beatWidth bits).setAsReg().init(0)
  val replayToken = UInt(3 bits).setAsReg().init(0)
  val replayRowIndex = UInt(16 bits).setAsReg().init(0)
  val scaleCount = UInt(beatWidth bits).setAsReg().init(0)
  val replayScaleBeat = UInt(beatWidth bits).setAsReg().init(0)
  val replayScaleToken = UInt(3 bits).setAsReg().init(0)
  val weightInputDone = Bool().setAsReg().init(False)
  val scaleInputDone = Bool().setAsReg().init(False)
  val weightReplayDone = Bool().setAsReg().init(False)
  val scaleReplayDone = Bool().setAsReg().init(False)

  val errorReg = Bool().setAsReg().init(False)
  val errorCodeReg = Bits(4 bits).setAsReg().init(0)
  val doneReg = Bool().setAsReg().init(False)
  val inputWeightBeatsReg = UInt(32 bits).setAsReg().init(0)
  val logicalOperandReplaysReg = UInt(32 bits).setAsReg().init(0)

  // The activation tile is written directly into MulEngine's enlarged RAM.
  // The only local storage needed here is one quantized/dequantized weight
  // row, which makes the K replay independent of DDR weight traffic.
  val weightRow = Mem(Bits(parallelBit bits), maxFirstDim)
  weightRow.addAttribute("ram_style", "distributed")
  val scaleRow = Mem(Bits(32 bits), maxFirstDim)
  scaleRow.addAttribute("ram_style", "distributed")

  val cfgDot = !io.cfgIn.payload(24)
  val requestedKValid = io.k >= 1 && io.k <= maxK
  val cfgFirstDim = io.cfgIn.payload(23 downto 16).asUInt
  val cfgBeats = cfgFirstDim + 1
  val cfgRows = io.cfgIn.payload(15 downto 0).asUInt + 1
  val cfgShapeValid = cfgBeats >= 1 && cfgBeats <= maxFirstDim
  val cfgActivationCount = (cfgBeats * io.k.resize(cfgBeats.getWidth)).resize(countWidth)

  val useSpecConfig = io.mode && cfgDot
  val cfgInvalid = useSpecConfig && (!requestedKValid || !cfgShapeValid)

  // A valid configuration starts only after the downstream banks accept it.
  // An invalid speculative configuration is consumed locally into the error
  // state, but is never presented as a cfgOut transfer.
  io.cfgOut.valid := io.cfgIn.valid && (state === idle) && !cfgInvalid
  io.cfgOut.payload := io.cfgIn.payload
  io.cfgIn.ready := (state === idle) && (cfgInvalid || io.cfgOut.ready)

  val cfgFire = io.cfgOut.fire
  val invalidCfgFire = io.cfgIn.fire && cfgInvalid

  // Legacy path: no buffering, no added latency, and no counter activity.
  val legacyPath = (state === idle) && !io.mode
  val idleBypassPath = (state === idle) && io.mode && !useSpecConfig

  io.dotOut.valid := False
  io.dotOut.payload := io.dotIn.payload
  io.dotIn.ready := False
  when(legacyPath || idleBypassPath) {
    io.dotOut.valid := io.dotIn.valid
    io.dotIn.ready := io.dotOut.ready
  } elsewhen (state === loadActivation) {
    io.dotOut.valid := io.dotIn.valid
    io.dotIn.ready := io.dotOut.ready
  }

  io.wkvOut.valid := False
  io.wkvOut.payload := weightRow.readAsync(replayBeat)
  io.wkvIn.ready := False
  when(legacyPath || idleBypassPath) {
    io.wkvOut.valid := io.wkvIn.valid
    io.wkvIn.ready := io.wkvOut.ready
  } elsewhen (state === loadRow) {
    io.wkvIn.ready := True
  } elsewhen (state === replayWeights) {
    io.wkvOut.valid := !weightReplayDone
  }

  io.postScaleOut.valid := False
  io.postScaleOut.payload := scaleRow.readAsync(replayScaleBeat)
  io.postScaleIn.ready := False
  when(legacyPath || idleBypassPath) {
    io.postScaleOut.valid := io.postScaleIn.valid
    io.postScaleIn.ready := io.postScaleOut.ready
  } elsewhen (state === loadRow) {
    io.postScaleIn.ready := !scaleInputDone
  } elsewhen (state === replayWeights) {
    io.postScaleOut.valid := !scaleReplayDone
  }

  val activationFire = io.dotIn.fire && (state === loadActivation)
  val weightInputFire = io.wkvIn.fire && (state === loadRow) && !weightInputDone
  val scaleInputFire = io.postScaleIn.fire && (state === loadRow) && !scaleInputDone
  val replayFire = io.wkvOut.fire && (state === replayWeights)
  val replayScaleFire = io.postScaleOut.fire && (state === replayWeights)

  io.inputWeightBeats := inputWeightBeatsReg
  io.logicalOperandReplays := logicalOperandReplaysReg
  io.active := (state === loadActivation) || (state === loadRow) || (state === replayWeights)
  io.replayDone := doneReg
  io.error := errorReg || (state === errorState)
  io.errorCode := errorCodeReg

  when(invalidCfgFire) {
    state := errorState
    errorReg := True
    errorCodeReg := Mux(!requestedKValid, B(1, 4 bits), B(2, 4 bits))
  }

  when(cfgFire) {
    when(useSpecConfig) {
      kReg := io.k
      beatsReg := cfgBeats.resized
      rowsReg := cfgRows.resized
      activationCount.clearAll()
      weightCount.clearAll()
      scaleCount.clearAll()
      replayBeat.clearAll()
      replayToken.clearAll()
      replayScaleBeat.clearAll()
      replayScaleToken.clearAll()
      replayRowIndex.clearAll()
      weightInputDone.clear()
      scaleInputDone.clear()
      weightReplayDone.clear()
      scaleReplayDone.clear()
      inputWeightBeatsReg.clearAll()
      logicalOperandReplaysReg.clearAll()
      errorReg.clear()
      errorCodeReg.clearAll()
      state := loadActivation
    }
  }

  when(activationFire) {
    when(activationCount === (cfgActivationCount - 1)) {
      activationCount.clearAll()
      weightCount.clearAll()
      state := loadRow
    } otherwise {
      activationCount := activationCount + 1
    }
  }

  when(weightInputFire) {
    weightRow.write(weightCount, io.wkvIn.payload)
    inputWeightBeatsReg := inputWeightBeatsReg + 1
    when(weightCount === (beatsReg - 1)) {
      weightCount.clearAll()
      weightInputDone.set()
    } otherwise {
      weightCount := weightCount + 1
    }
  }

  when(scaleInputFire) {
    scaleRow.write(scaleCount, io.postScaleIn.payload)
    when(scaleCount === (beatsReg - 1)) {
      scaleCount.clearAll()
      scaleInputDone.set()
    } otherwise {
      scaleCount := scaleCount + 1
    }
  }

  // Weight and scale input are independent streams.  Do not begin replay
  // until both copies of the current physical row are complete.
  val weightInputComplete = weightInputDone ||
    (weightInputFire && (weightCount === (beatsReg - 1)))
  val scaleInputComplete = scaleInputDone ||
    (scaleInputFire && (scaleCount === (beatsReg - 1)))
  when(state === loadRow && weightInputComplete && scaleInputComplete) {
    replayBeat.clearAll()
    replayToken.clearAll()
    replayScaleBeat.clearAll()
    replayScaleToken.clearAll()
    weightReplayDone.clear()
    scaleReplayDone.clear()
    state := replayWeights
  }

  when(replayFire) {
    logicalOperandReplaysReg := logicalOperandReplaysReg + 1
    when(replayBeat === (beatsReg - 1)) {
      replayBeat.clearAll()
      when(replayToken === (kReg - 1)) {
        replayToken.clearAll()
        weightReplayDone.set()
      } otherwise {
        replayToken := replayToken + 1
      }
    } otherwise {
      replayBeat := replayBeat + 1
    }
  }

  when(replayScaleFire) {
    when(replayScaleBeat === (beatsReg - 1)) {
      replayScaleBeat.clearAll()
      when(replayScaleToken === (kReg - 1)) {
        replayScaleToken.clearAll()
        scaleReplayDone.set()
      } otherwise {
        replayScaleToken := replayScaleToken + 1
      }
    } otherwise {
      replayScaleBeat := replayScaleBeat + 1
    }
  }

  val weightReplayComplete = weightReplayDone ||
    (replayFire && (replayBeat === (beatsReg - 1)) &&
      (replayToken === (kReg - 1)))
  val scaleReplayComplete = scaleReplayDone ||
    (replayScaleFire && (replayScaleBeat === (beatsReg - 1)) &&
      (replayScaleToken === (kReg - 1)))
  when(state === replayWeights && weightReplayComplete && scaleReplayComplete) {
    when(replayRowIndex === (rowsReg - 1)) {
      doneReg.set()
      state := doneState
    } otherwise {
      replayRowIndex := replayRowIndex + 1
      weightCount.clearAll()
      scaleCount.clearAll()
      weightInputDone.clear()
      scaleInputDone.clear()
      state := loadRow
    }
  }

  when(state === doneState) {
    // One-cycle status pulse.  The next configuration can be accepted after
    // this pulse, while projection completion remains owned by AddEngine.
    doneReg.clear()
    state := idle
  }

  when(state =/= doneState &&
    !(state === replayWeights && weightReplayComplete && scaleReplayComplete &&
      replayRowIndex === (rowsReg - 1))) {
    doneReg.clear()
  }
}

/** Structural entry point for the focused P2 production-front-end check. */
object SpeculativeGemmReplayTest extends App {
  SpinalConfig(
    targetDirectory = "../kv260/speculative/build/p2_production_replay_elab",
    oneFilePerComponent = false
  ).generateVerilog(new SpeculativeGemmReplay(width = 16, bankLen = 8, maxFirstDim = 32))
}
