package attn

import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axis._
import util.GenAxiDataMoverCmd

import scala.language.postfixOps

/** Aligned DDR fetch and ping/pong replay for packed KV scale/zero entries. */
class P4KvMetadataRequester(
                            busWidth: Int,
                            maxContext: Int,
                            tileTokens: Int = 64
                          ) extends Component {
  require(busWidth % 32 == 0)
  private val beatBytes = busWidth / 8
  private val entriesPerBeat = busWidth / 32
  require(isPow2(entriesPerBeat))
  private val entryBits = log2Up(entriesPerBeat)
  private val countWidth = log2Up(tileTokens + 1)
  private val lineCountWidth = log2Up(tileTokens / entriesPerBeat + 2)

  val ddrCfg = Axi4StreamConfig(dataWidth = beatBytes, useKeep = true, useLast = true)
  val io = new Bundle {
    val request = slave(Stream(P4AttentionTile(maxContext)))
    val baseAddress = in UInt(32 bits)
    val commandTag = in Bits(4 bits)
    val ddrCmd = master(Stream(Bits(72 bits)))
    val ddrData = slave(Axi4Stream(ddrCfg))
    val metadata = master(Stream(Bits(32 bits)))
    val done = master(Flow(P4AttentionTileDone(maxContext)))
    val busy = out Bool()
    val error = out Bool()
  }

  val idle = U(0, 3 bits)
  val issue = U(1, 3 bits)
  val fetchLine = U(2, 3 bits)
  val emitLine = U(3, 3 bits)
  val replay = U(4, 3 bits)
  val state = Reg(UInt(3 bits)) init idle
  val requestReg = Reg(P4AttentionTile(maxContext))
  val baseReg = Reg(UInt(32 bits)) init 0
  val commandTagReg = Reg(Bits(4 bits)) init 0
  val alignedTokenReg = Reg(UInt(log2Up(maxContext + 1) bits)) init 0
  val linesReg = Reg(UInt(lineCountWidth bits)) init 0
  val lineIndex = Reg(UInt(lineCountWidth bits)) init 0
  val entryIndex = Reg(UInt(entryBits bits)) init 0
  val emitted = Reg(UInt(countWidth bits)) init 0
  val lineData = Reg(Bits(busWidth bits)) init 0
  val errorReg = Reg(Bool()) init False

  val ping = Mem(Bits(32 bits), tileTokens)
  val pong = Mem(Bits(32 bits), tileTokens)
  ping.addAttribute("ram_style", "block")
  pong.addAttribute("ram_style", "block")

  val startLow = io.request.payload.startToken.takeLow(entryBits).asUInt
  val alignedToken = (io.request.payload.startToken >> entryBits) << entryBits
  val coveredEntries = startLow.resize(countWidth + 1) + io.request.payload.tokenCount.resize(countWidth + 1)
  val lineCount = ((coveredEntries + entriesPerBeat - 1) >> entryBits).resize(lineCountWidth)
  val replayData = Mux(requestReg.buffer,
    pong.readAsync(emitted.resized), ping.readAsync(emitted.resized))
  val selectedEntry = lineData.subdivideIn(32 bits)(entryIndex)

  io.request.ready := state === idle
  io.ddrCmd.valid := state === issue
  io.ddrCmd.payload := GenAxiDataMoverCmd(
    alignedTokenReg.resize(32) * U(4, 32 bits),
    linesReg.resize(32) * U(beatBytes, 32 bits),
    baseReg, inc = True, eof = True, tag = commandTagReg)
  io.ddrData.ready := state === fetchLine
  io.metadata.valid := state === emitLine || state === replay
  io.metadata.payload := Mux(state === replay, replayData, selectedEntry)
  io.done.valid := False
  io.done.payload.phase := requestReg.phase
  io.done.payload.source := requestReg.source
  io.done.payload.buffer := requestReg.buffer
  io.done.payload.query := requestReg.query
  io.done.payload.startToken := requestReg.startToken
  io.busy := state =/= idle
  io.error := errorReg

  when(io.request.fire) {
    requestReg := io.request.payload
    baseReg := io.baseAddress
    commandTagReg := io.commandTag
    alignedTokenReg := alignedToken
    linesReg := lineCount
    lineIndex.clearAll()
    emitted.clearAll()
    entryIndex := startLow
    when(io.request.payload.tokenCount === 0 || io.request.payload.tokenCount > tileTokens) {
      errorReg.set()
    } elsewhen(io.request.payload.fetch) {
      state := issue
    } otherwise {
      state := replay
    }
  }

  when(io.ddrCmd.fire) { state := fetchLine }

  when(io.ddrData.fire) {
    lineData := io.ddrData.data
    val expectedLast = lineIndex === linesReg - 1
    when(io.ddrData.last =/= expectedLast) { errorReg.set() }
    state := emitLine
  }
  when(io.ddrData.valid && state =/= fetchLine) { errorReg.set() }

  when(io.metadata.fire) {
    when(state === emitLine) {
      when(requestReg.buffer) { pong.write(emitted.resized, selectedEntry) } otherwise {
        ping.write(emitted.resized, selectedEntry)
      }
    }
    val finalEntry = emitted === requestReg.tokenCount.resize(countWidth) - 1
    when(finalEntry) {
      io.done.valid := True
      state := idle
      emitted.clearAll()
    } otherwise {
      emitted := emitted + 1
      when(state === emitLine) {
        when(entryIndex === entriesPerBeat - 1) {
          entryIndex.clearAll()
          lineIndex := lineIndex + 1
          state := fetchLine
        } otherwise {
          entryIndex := entryIndex + 1
        }
      }
    }
  }
}

object P4KvMetadataRequesterTest extends App {
  SpinalConfig(
    targetDirectory = "../kv260/speculative/build/p4h_kv_metadata_requester_elab",
    oneFilePerComponent = true
  ).generateVerilog(new P4KvMetadataRequester(512, 1024, 64))
}
