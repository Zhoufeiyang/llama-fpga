package top

import spinal.core._
import spinal.lib._

import scala.language.postfixOps

/**
  * A row-major sideband router for the shared P2 result stream.
  *
  * One input beat is routed to exactly one lane output.  The input is held
  * until that output accepts it, so no tensor-sized transpose buffer is
  * required.  A row consists of one contiguous beat group per lane; rowLast
  * terminates a lane's group and batchLast terminates the final lane group.
  * The row and lane metadata is copied unchanged to the selected output.
  */
case class P2RowMajorBeat(dataWidth: Int, laneWidth: Int, rowWidth: Int) extends Bundle {
  val data = Bits(dataWidth bits)
  val laneId = UInt(laneWidth bits)
  val rowIndex = UInt(rowWidth bits)
  val rowLast = Bool()
  val batchLast = Bool()
}

class P2RowMajorLaneRouter(
                           dataWidth: Int,
                           rowWidth: Int = 16,
                           maxK: Int = 4
                         ) extends Component {
  require(dataWidth >= 1)
  require(rowWidth >= 1)
  require(maxK >= 1 && maxK <= 4)
  require(isPow2(maxK))

  val laneWidth = if (maxK <= 1) 1 else log2Up(maxK)
  val beatType = P2RowMajorBeat(dataWidth, laneWidth, rowWidth)

  val io = new Bundle {
    val k = in UInt(3 bits)
    val input = slave(Stream(beatType))
    val outputs = Vec(master(Stream(beatType)), maxK)
    // Invalid K/lane or row-boundary metadata is reported here.  Keeping a
    // fault stream makes malformed traffic drainable under ready/valid.
    val fault = master(Stream(beatType))
    val batchActive = out Bool()
    val batchDone = out Bool()
    val error = out Bool()
    val errorCode = out Bits(4 bits)
  }

  noIoPrefix()
  util.AxiStreamSpecRenamer(io.input)
  io.outputs.foreach(util.AxiStreamSpecRenamer(_))
  util.AxiStreamSpecRenamer(io.fault)

  val batchActiveReg = Bool().setAsReg().init(False)
  val kReg = UInt(3 bits).setAsReg().init(1)
  val expectedLane = UInt(laneWidth bits).setAsReg().init(0)
  val expectedRow = UInt(rowWidth bits).setAsReg().init(0)
  val errorReg = Bool().setAsReg().init(False)
  val errorCodeReg = Bits(4 bits).setAsReg().init(0)
  val batchDoneReg = Bool().setAsReg().init(False)

  val activeK = UInt(3 bits)
  activeK := Mux(batchActiveReg, kReg, io.k)
  val kValid = activeK >= 1 && activeK <= maxK
  val laneInRange = io.input.payload.laneId.resize(4) < activeK.resize(4)
  val routeFault = !kValid || !laneInRange

  io.outputs.foreach { output =>
    output.payload := io.input.payload
  }
  io.fault.valid := io.input.valid && routeFault
  io.fault.payload := io.input.payload

  val selectedReady = Bool()
  selectedReady := False
  for (lane <- 0 until maxK) {
    val hit = !routeFault && io.input.payload.laneId === lane
    io.outputs(lane).valid := io.input.valid && hit
    when(hit) {
      selectedReady := io.outputs(lane).ready
    }
  }
  io.input.ready := Mux(routeFault, io.fault.ready, selectedReady)

  val inputFire = io.input.fire
  val routeAccepted = inputFire && !routeFault
  val faultFire = inputFire && routeFault
  val metadataOrderOk = io.input.payload.laneId === expectedLane &&
    io.input.payload.rowIndex === expectedRow
  val boundaryOk = Mux(
    io.input.payload.rowLast,
    !io.input.payload.batchLast || (expectedLane === (activeK - 1).resize(laneWidth)),
    !io.input.payload.batchLast
  )
  val metadataError = routeAccepted && (!metadataOrderOk || !boundaryOk)

  io.batchActive := batchActiveReg
  io.batchDone := batchDoneReg
  io.error := errorReg
  io.errorCode := errorCodeReg

  // Status is sticky for the batch; batchDone is a one-cycle pulse.
  batchDoneReg.clear()
  when(faultFire) {
    when(!kValid) {
      errorReg.set()
      when(!errorReg)(errorCodeReg := B(1, 4 bits))
    } otherwise {
      errorReg.set()
      when(!errorReg)(errorCodeReg := B(2, 4 bits))
    }
  }
  when(metadataError) {
    errorReg.set()
    when(!errorReg) {
      when(!metadataOrderOk)(errorCodeReg := B(3, 4 bits))
        .otherwise(errorCodeReg := B(4, 4 bits))
    }
  }

  when(routeAccepted && !batchActiveReg) {
    kReg := io.k
    batchActiveReg.set()
  }

  when(routeAccepted && metadataOrderOk && boundaryOk) {
    when(io.input.payload.rowLast) {
      when(expectedLane === (activeK - 1).resize(laneWidth)) {
        expectedLane.clearAll()
        when(io.input.payload.batchLast) {
          expectedRow.clearAll()
          batchActiveReg.clear()
          batchDoneReg.set()
        } otherwise {
          expectedRow := expectedRow + 1
        }
      } otherwise {
        expectedLane := expectedLane + 1
      }
    }
  }

  // A fault beat is consumed but does not advance the expected row/lane
  // position.  Software can use errorCode to reject the enclosing batch.
}

object P2RowMajorLaneRouterGen extends App {
  SpinalConfig(
    targetDirectory = "../kv260/speculative/build/p2_row_major_lane_router_elab",
    oneFilePerComponent = false
  ).generateVerilog(new P2RowMajorLaneRouter(dataWidth = 32, rowWidth = 8, maxK = 4))
}
