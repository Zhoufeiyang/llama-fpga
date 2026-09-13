package cfgGen

import spinal.core._

/** Shared production selector for causal KV reads and future-slot KV writes. */
class SpeculativeKvPosition(maxToken: Int) extends Component {
  val width = log2Up(maxToken)
  val lengthWidth = log2Up(maxToken + 1)
  val io = new Bundle {
    val legacyPosition = in UInt(width bits)
    val speculativeEnable = in Bool()
    val committedPosition = in UInt(lengthWidth bits)
    val candidatePosition = in UInt(2 bits)
    val selectedPosition = out UInt(width bits)
    val overflow = out Bool()
  }

  val speculativePosition = io.committedPosition.resize(lengthWidth + 1) +
    io.candidatePosition.resize(lengthWidth + 1)
  io.selectedPosition := Mux(io.speculativeEnable, speculativePosition.resized, io.legacyPosition)
  io.overflow := io.speculativeEnable && speculativePosition >= maxToken
}

object SpeculativeKvPositionP5CTest extends App {
  SpinalConfig(
    targetDirectory = "../kv260/speculative/build/p5c_position_elab",
    oneFilePerComponent = false
  ).generateVerilog(new SpeculativeKvPosition(1024))
}
