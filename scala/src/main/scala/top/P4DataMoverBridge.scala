package top

import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axis._

import scala.language.postfixOps

/**
  * Logical MM2S command/response bridge for the legacy and P4 clients.
  *
  * The bridge owns no AXI memory-mapped signals.  It sits at the logical
  * 72-bit DataMover boundary, before any address remap or split command
  * adapter.  A command is accepted into a one-entry holding register and is
  * only retired when both owner FIFOs can record the same owner.  This makes
  * the P4-first arbitration independent of downstream backpressure while
  * preserving the command payload for the whole valid/stall interval.
  */
class P4DataMoverBridge(
                         busWidth: Int = 512,
                         dataOwnerDepth: Int = 32,
                         statusOwnerDepth: Int = 32
                       ) extends Component {
  require(busWidth == 512, "P4DataMoverBridge is defined for a 512-bit MM2S response")
  require(dataOwnerDepth > 0)
  require(statusOwnerDepth > 0)

  private val responseCfg = Axi4StreamConfig(
    dataWidth = busWidth / 8,
    useKeep = true,
    useLast = true
  )

  val io = new Bundle {
    val legacyCmd = slave(Stream(Bits(72 bits)))
    val p4Cmd = slave(Stream(Bits(72 bits)))
    val outCmd = master(Stream(Bits(72 bits)))

    val dmaResponse = slave(Axi4Stream(responseCfg))
    val legacyResponse = master(Axi4Stream(responseCfg))
    val p4Response = master(Axi4Stream(responseCfg))

    // SplitAxiDatamover exposes one aggregate 8-bit MM2S status stream.
    val dmaStatus = slave(Stream(Bits(8 bits)))
    val legacyStatus = master(Stream(Bits(8 bits)))
    val p4Status = master(Stream(Bits(8 bits)))

    val error = out Bool()
  }

  // Owner encoding: false = legacy, true = P4.
  val dataOwnerFifo = new StreamFifo(Bool(), dataOwnerDepth, forFMax = true)
  val statusOwnerFifo = new StreamFifo(Bool(), statusOwnerDepth, forFMax = true)

  val cmdHoldValid = Reg(Bool()) init False
  val cmdHoldOwner = Reg(Bool()) init False
  val cmdHoldPayload = Reg(Bits(72 bits)) init 0
  val errorReg = Reg(Bool()) init False
  val dataOutstanding = Reg(UInt(log2Up(dataOwnerDepth + 1) bits)) init 0
  val statusOutstanding = Reg(UInt(log2Up(statusOwnerDepth + 1) bits)) init 0

  // P4 has strict priority only when selecting an empty holding register.
  // Once selected, the payload is stored, so outCmd never changes owner or
  // payload while stalled.
  io.legacyCmd.ready := !cmdHoldValid && !io.p4Cmd.valid
  io.p4Cmd.ready := !cmdHoldValid

  when(io.p4Cmd.fire) {
    cmdHoldValid := True
    cmdHoldOwner := True
    cmdHoldPayload := io.p4Cmd.payload
  } elsewhen(io.legacyCmd.fire) {
    cmdHoldValid := True
    cmdHoldOwner := False
    cmdHoldPayload := io.legacyCmd.payload
  }

  val ownerFifosReady = dataOwnerFifo.io.push.ready && statusOwnerFifo.io.push.ready
  io.outCmd.valid := cmdHoldValid && ownerFifosReady
  io.outCmd.payload := cmdHoldPayload

  val outFire = io.outCmd.fire
  dataOwnerFifo.io.push.valid := outFire
  dataOwnerFifo.io.push.payload := cmdHoldOwner
  statusOwnerFifo.io.push.valid := outFire
  statusOwnerFifo.io.push.payload := cmdHoldOwner

  when(outFire) {
    cmdHoldValid := False
  }

  // These checks are intentionally retained even though outCmd.valid is
  // gated by both push.ready signals.  They turn a future wiring regression
  // into a sticky diagnostic rather than silently losing an owner token.
  when(outFire && (!dataOwnerFifo.io.push.ready || !statusOwnerFifo.io.push.ready)) {
    errorReg.set()
  }

  // Data response routing is owner-ordered and frame-aware.  The owner token
  // stays in the FIFO for every beat and is popped only with an accepted last
  // beat.  No owner means no ready is returned to DMA; valid then records an
  // underflow while the producer remains safely backpressured.
  val dataOwnerValid = dataOwnerFifo.io.pop.valid
  val dataOwnerP4 = dataOwnerFifo.io.pop.payload

  io.legacyResponse.valid := io.dmaResponse.valid && dataOwnerValid && !dataOwnerP4
  io.p4Response.valid := io.dmaResponse.valid && dataOwnerValid && dataOwnerP4
  io.legacyResponse.data := io.dmaResponse.data
  io.legacyResponse.keep := io.dmaResponse.keep
  io.legacyResponse.last := io.dmaResponse.last
  io.p4Response.data := io.dmaResponse.data
  io.p4Response.keep := io.dmaResponse.keep
  io.p4Response.last := io.dmaResponse.last

  io.dmaResponse.ready := False
  when(dataOwnerValid) {
    io.dmaResponse.ready := Mux(dataOwnerP4,
      io.p4Response.ready,
      io.legacyResponse.ready)
  }
  val dataResponseFire = io.dmaResponse.fire
  dataOwnerFifo.io.pop.ready := dataResponseFire && io.dmaResponse.last

  val dataRetire = dataResponseFire && io.dmaResponse.last
  switch(outFire ## dataRetire) {
    is(B"10") { dataOutstanding := dataOutstanding + 1 }
    is(B"01") { dataOutstanding := dataOutstanding - 1 }
  }

  // forFMax FIFOs may take a cycle to expose a just-enqueued owner.  That is
  // legal backpressure, not underflow; only a response with no accepted
  // command outstanding is a protocol violation.
  when(io.dmaResponse.valid && dataOutstanding === 0 && !outFire) {
    errorReg.set()
  }

  // Status is a one-beat event stream.  Its owner token is consumed exactly
  // when the selected client accepts that event; status backpressure cannot
  // accidentally retire a data frame or vice versa.
  val statusOwnerValid = statusOwnerFifo.io.pop.valid
  val statusOwnerP4 = statusOwnerFifo.io.pop.payload
  io.legacyStatus.valid := io.dmaStatus.valid && statusOwnerValid && !statusOwnerP4
  io.p4Status.valid := io.dmaStatus.valid && statusOwnerValid && statusOwnerP4
  io.legacyStatus.payload := io.dmaStatus.payload
  io.p4Status.payload := io.dmaStatus.payload

  io.dmaStatus.ready := False
  when(statusOwnerValid) {
    io.dmaStatus.ready := Mux(statusOwnerP4,
      io.p4Status.ready,
      io.legacyStatus.ready)
  }
  val statusFire = io.dmaStatus.fire
  statusOwnerFifo.io.pop.ready := statusFire

  switch(outFire ## statusFire) {
    is(B"10") { statusOutstanding := statusOutstanding + 1 }
    is(B"01") { statusOutstanding := statusOutstanding - 1 }
  }

  when(io.dmaStatus.valid && statusOutstanding === 0 && !outFire) {
    errorReg.set()
  }

  io.error := errorReg
}

/** Focused elaboration entry point; the SV testbench is p4j_*.sv. */
object P4DataMoverBridgeTest extends App {
  SpinalConfig(
    targetDirectory = "../kv260/speculative/build/p4j_p4_datamover_bridge_elab",
    oneFilePerComponent = true
  ).generateVerilog(new P4DataMoverBridge())
}
