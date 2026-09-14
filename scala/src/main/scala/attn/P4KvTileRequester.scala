package attn

import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axis._
import util.GenAxiDataMoverCmd

import scala.language.postfixOps

/** Physical value-tile transport for speculative attention.
  *
  * A fetch request emits one DataMover command and writes the returned beats
  * into the selected ping/pong memory while forwarding them to the existing
  * attention input.  A replay request emits no DDR command and reads the same
  * beats from the selected memory.  Completion is tied to the final accepted
  * output beat, so downstream backpressure cannot retire a tile early.
  *
  * Scale/zero metadata uses the same transport contract through a separate
  * instance with bytesPerToken=4; the production wrapper sequences metadata
  * before values and assigns the corresponding cache tag.
  */
class P4KvTileRequester(
                         busWidth: Int,
                         maxContext: Int,
                         tileTokens: Int = 64,
                         bytesPerToken: Int = 128
                       ) extends Component {
  require(busWidth % 8 == 0)
  require(tileTokens > 0 && tileTokens <= maxContext)
  require(bytesPerToken > 0)
  require((tileTokens * bytesPerToken) % (busWidth / 8) == 0)

  private val beatBytes = busWidth / 8
  private val maxBeats = tileTokens * bytesPerToken / beatBytes
  private val beatWidth = log2Up(maxBeats + 1)
  private val contextWidth = log2Up(maxContext + 1)

  val axisCfg = Axi4StreamConfig(dataWidth = beatBytes, useLast = true, useKeep = true)
  val localCfg = Axi4StreamConfig(dataWidth = beatBytes, destWidth = 6, useDest = true, useLast = true)

  val io = new Bundle {
    val request = slave(Stream(P4AttentionTile(maxContext)))
    val baseAddress = in UInt(32 bits)
    // Logical SplitAxiDatamover stripe selector. KV values use tag 1 and
    // packed scale/zero metadata uses tag 2 in the current memory map.
    val commandTag = in Bits(4 bits)
    val outputTag = in Bits(6 bits)
    val ddrCmd = master(Stream(Bits(72 bits)))
    val ddrData = slave(Axi4Stream(axisCfg))
    val output = master(Axi4Stream(localCfg))
    val done = master(Flow(P4AttentionTileDone(maxContext)))
    val busy = out Bool()
    val error = out Bool()
  }

  val idle = U(0, 2 bits)
  val issue = U(1, 2 bits)
  val fetch = U(2, 2 bits)
  val replay = U(3, 2 bits)
  val state = Reg(UInt(2 bits)) init idle

  val requestReg = Reg(P4AttentionTile(maxContext))
  val baseAddressReg = Reg(UInt(32 bits)) init 0
  val commandTagReg = Reg(Bits(4 bits)) init 0
  val outputTagReg = Reg(Bits(6 bits)) init 0
  val beatCount = Reg(UInt(beatWidth bits)) init 0
  val expectedBeats = Reg(UInt(beatWidth bits)) init 0
  val errorReg = Reg(Bool()) init False

  val ping = Mem(Bits(busWidth bits), maxBeats)
  val pong = Mem(Bits(busWidth bits), maxBeats)
  ping.addAttribute("ram_style", "block")
  pong.addAttribute("ram_style", "block")

  val requestBytes = io.request.payload.tokenCount.resize(32) * U(bytesPerToken, 32 bits)
  val requestBeats = (requestBytes / beatBytes).resize(beatWidth)

  io.request.ready := state === idle
  io.ddrCmd.valid := state === issue
  io.ddrCmd.payload := GenAxiDataMoverCmd(
    requestReg.startToken.resize(32) * U(bytesPerToken, 32 bits),
    requestReg.tokenCount.resize(32) * U(bytesPerToken, 32 bits),
    baseAddressReg,
    inc = True,
    eof = True,
    tag = commandTagReg
  )

  val replayData = Bits(busWidth bits)
  replayData := Mux(requestReg.buffer, pong.readAsync(beatCount.resized), ping.readAsync(beatCount.resized))

  io.output.valid := (state === fetch && io.ddrData.valid) || state === replay
  io.output.data := Mux(state === replay, replayData, io.ddrData.data)
  io.output.dest := outputTagReg.asUInt
  io.output.last := expectedBeats =/= 0 && beatCount === expectedBeats - 1
  io.ddrData.ready := state === fetch && io.output.ready

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
    baseAddressReg := io.baseAddress
    commandTagReg := io.commandTag
    outputTagReg := io.outputTag
    beatCount.clearAll()
    expectedBeats := requestBeats
    when(io.request.payload.tokenCount === 0 || requestBeats === 0 || requestBeats > maxBeats) {
      errorReg.set()
    } elsewhen(io.request.payload.fetch) {
      state := issue
    } otherwise {
      state := replay
    }
  }

  when(io.ddrCmd.fire) {
    state := fetch
  }

  val outputFire = io.output.fire
  when(outputFire && state === fetch) {
    when(requestReg.buffer) {
      pong.write(beatCount.resized, io.ddrData.data)
    } otherwise {
      ping.write(beatCount.resized, io.ddrData.data)
    }
    when(io.ddrData.last =/= io.output.last) {
      errorReg.set()
    }
  }

  when(outputFire) {
    when(io.output.last) {
      io.done.valid := True
      beatCount.clearAll()
      state := idle
    } otherwise {
      beatCount := beatCount + 1
    }
  }

  // A DataMover response is legal only after this requester issued a fetch.
  when(io.ddrData.valid && state =/= fetch) {
    errorReg.set()
  }
}

object P4KvTileRequesterTest extends App {
  SpinalConfig(
    targetDirectory = "../kv260/speculative/build/p4g_kv_tile_requester_elab",
    oneFilePerComponent = true
  ).generateVerilog(new P4KvTileRequester(
    busWidth = 128,
    maxContext = 1024,
    tileTokens = 64,
    bytesPerToken = 128
  ))
}
