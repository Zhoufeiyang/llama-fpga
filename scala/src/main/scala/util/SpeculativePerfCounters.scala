package util

import spinal.core._

import scala.language.postfixOps

/** Passive, transaction-scoped counters for publication measurements.
  *
  * Events are supplied by the production command/data path.  Counters never
  * participate in ready/valid, so enabling measurement cannot alter traffic.
  */
class SpeculativePerfCounters(bytesPerBeat: Int) extends Component {
  require(bytesPerBeat > 0)

  val io = new Bundle {
    val clear = in Bool()
    val active = in Bool()
    val weightBeat = in Bool()
    val kvReadBeat = in Bool()
    val kvWriteBeat = in Bool()
    val memoryStall = in Bool()
    val resultStall = in Bool()

    val weightBytes = out UInt(64 bits)
    val kvReadBytes = out UInt(64 bits)
    val kvWriteBytes = out UInt(64 bits)
    val verifyCycles = out UInt(64 bits)
    val memoryStallCycles = out UInt(64 bits)
    val resultStallCycles = out UInt(64 bits)
  }

  private def counter(event: Bool, increment: BigInt = 1): UInt = {
    val value = Reg(UInt(64 bits)) init 0
    when(io.clear) {
      value.clearAll()
    } elsewhen (io.active && event) {
      value := value + U(increment, 64 bits)
    }
    value
  }

  io.weightBytes := counter(io.weightBeat, bytesPerBeat)
  io.kvReadBytes := counter(io.kvReadBeat, bytesPerBeat)
  io.kvWriteBytes := counter(io.kvWriteBeat, bytesPerBeat)
  io.verifyCycles := counter(True)
  io.memoryStallCycles := counter(io.memoryStall)
  io.resultStallCycles := counter(io.resultStall)
}

object SpeculativePerfCountersTest extends App {
  SpinalConfig(
    targetDirectory = "../kv260/speculative/build/p7g_perf_counter_elab",
    oneFilePerComponent = false
  ).generateVerilog(new SpeculativePerfCounters(bytesPerBeat = 64))
}
