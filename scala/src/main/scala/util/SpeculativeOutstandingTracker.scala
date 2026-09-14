package util

import spinal.core._

import scala.language.postfixOps

/** Epoch-scoped drain barrier for irreversible DMA traffic.
  *
  * Issues are counted when a speculative command enters the production
  * command FIFO. Retires are counted at the corresponding accepted data-last
  * boundary. Disabling/rolling back a transaction does not discard the count:
  * the old physical traffic must drain before another epoch may start.
  */
class SpeculativeOutstandingTracker(countWidth: Int = 16, epochWidth: Int = 8) extends Component {
  val io = new Bundle {
    val active = in Bool()
    val epoch = in UInt(epochWidth bits)
    val issue = in Bool()
    val retire = in Bool()
    val clearError = in Bool()
    val outstanding = out UInt(countWidth bits)
    val drained = out Bool()
    val error = out Bool()
  }

  val count = Reg(UInt(countWidth bits)) init 0
  val trackedEpoch = Reg(UInt(epochWidth bits)) init 0
  val tracking = Reg(Bool()) init False
  val errorReg = Reg(Bool()) init False

  io.outstanding := count
  io.drained := count === 0
  io.error := errorReg

  when(io.clearError && count === 0) {
    errorReg.clear()
  }

  when(tracking && count =/= 0 && io.active && io.epoch =/= trackedEpoch) {
    errorReg.set()
  }
  when(io.issue && (!io.active || (tracking && io.epoch =/= trackedEpoch))) {
    errorReg.set()
  }
  when(io.retire && count === 0) {
    errorReg.set()
  }

  switch(io.issue ## io.retire) {
    is(B"10") {
      when(count =/= count.maxValue) { count := count + 1 } otherwise { errorReg.set() }
      when(!tracking) {
        tracking.set()
        trackedEpoch := io.epoch
      }
    }
    is(B"01") {
      when(count =/= 0) {
        count := count - 1
        when(count === 1) { tracking.clear() }
      }
    }
    is(B"11") {
      // One command replaces one completion. Preserve the epoch identity;
      // this case is legal only while the same transaction remains active.
      when(!tracking) {
        tracking.set()
        trackedEpoch := io.epoch
      }
    }
  }
}

object SpeculativeOutstandingTrackerTest extends App {
  SpinalConfig(
    targetDirectory = "../kv260/speculative/build/p5g_outstanding_tracker_elab",
    oneFilePerComponent = true
  ).generateVerilog(new SpeculativeOutstandingTracker(countWidth = 8, epochWidth = 8))
}
