package cfgGen

import spinal.core._

import scala.language.postfixOps

/** Descriptor grant for the production MM2S command boundary. */
class SpeculativeProjectionCommandGate(weightTags: Seq[Int]) extends Component {
  require(weightTags.nonEmpty)
  require(weightTags.forall(tag => tag >= 0 && tag < 64))

  val io = new Bundle {
    val speculativeActive = in Bool()
    val descriptorActive = in Bool()
    val descriptorTag = in Bits(6 bits)
    val commandTag = in Bits(6 bits)
    val allow = out Bool()
    val weightCommand = out Bool()
  }

  val weightCommand = weightTags.map(tag => io.commandTag === B(tag, 6 bits)).reduce(_ || _)
  io.weightCommand := weightCommand
  io.allow := !io.speculativeActive || !weightCommand ||
    (io.descriptorActive && io.commandTag === io.descriptorTag)
}

object SpeculativeProjectionCommandGateTest extends App {
  SpinalConfig(
    targetDirectory = "../kv260/speculative/build/p3d_projection_command_gate_elab",
    oneFilePerComponent = false
  ).generateVerilog(new SpeculativeProjectionCommandGate(Seq(4, 5, 7, 11, 15, 16, 17, 19)))
}
