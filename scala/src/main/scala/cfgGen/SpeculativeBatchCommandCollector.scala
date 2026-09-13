package cfgGen

import spinal.core._

import scala.language.postfixOps

/**
  * Transaction-scoped front-end command collector.
  *
  * The production command mux must remain on the token/embedding segment until
  * every candidate command has actually handshaken.  `advance` is therefore a
  * one-cycle pulse on the Kth accepted command, never on merely asserted valid.
  * Epoch changes and transaction aborts discard a partial count.
  */
class SpeculativeBatchCommandCollector(maxK: Int = 4) extends Component {
  require(maxK >= 1 && maxK <= 7)

  val io = new Bundle {
    val active = in Bool()
    val k = in UInt(log2Up(maxK + 1) bits)
    val epoch = in UInt(8 bits)
    val commandFire = in Bool()
    val advance = out Bool()
    val accepted = out UInt(log2Up(maxK + 1) bits)
    val error = out Bool()
  }

  val epochReg = Reg(UInt(8 bits)) init 0
  val acceptedReg = Reg(UInt(log2Up(maxK + 1) bits)) init 0
  val errorReg = Reg(Bool()) init False
  val epochChanged = io.epoch =/= epochReg
  val validK = io.k >= 1 && io.k <= maxK

  io.advance := False
  when(!io.active) {
    acceptedReg.clearAll()
    epochReg := io.epoch
  } elsewhen(epochChanged) {
    acceptedReg.clearAll()
    epochReg := io.epoch
    errorReg.clear()
    when(!validK) {
      errorReg.set()
    } elsewhen(io.commandFire) {
      when(io.k === 1) {
        io.advance := True
      } otherwise {
        acceptedReg := 1
      }
    }
  } otherwise {
    when(!validK) {
      errorReg.set()
    } elsewhen(io.commandFire) {
      when(acceptedReg === (io.k - 1).resized) {
        io.advance := True
        acceptedReg.clearAll()
      } otherwise {
        acceptedReg := acceptedReg + 1
      }
    }
  }

  io.accepted := acceptedReg
  io.error := errorReg
}

object SpeculativeBatchCommandCollectorTest extends App {
  SpinalConfig(
    targetDirectory = "../kv260/speculative/build/p3c_batch_collector_elab",
    oneFilePerComponent = false
  ).generateVerilog(new SpeculativeBatchCommandCollector())
}
