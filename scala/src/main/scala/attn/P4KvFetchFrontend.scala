package attn

import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axis._

import scala.language.postfixOps

/**
  * Serialized KV tile fetch front-end.
  *
  * The two requesters deliberately share one logical DataMover command stream
  * and one 512-bit response stream.  Metadata is always the first owner of a
  * transaction; the value requester becomes the owner only after metadata has
  * produced its final entry.  Consequently a response beat can never be
  * consumed by the wrong requester, and fetch=false requests never reach the
  * command port because both child requesters enter their local replay state.
  */
class P4KvFetchFrontend(
                         busWidth: Int,
                         maxContext: Int,
                         tileTokens: Int = 64,
                         valueBytesPerToken: Int = 128
                       ) extends Component {
  require(busWidth % 32 == 0)
  require(tileTokens > 0 && tileTokens <= maxContext)
  require(valueBytesPerToken > 0)
  require((tileTokens * valueBytesPerToken) % (busWidth / 8) == 0)

  private val beatBytes = busWidth / 8
  private val contextWidth = log2Up(maxContext + 1)

  private val ddrCfg = Axi4StreamConfig(
    dataWidth = beatBytes,
    useKeep = true,
    useLast = true
  )
  // P4KvTileRequester intentionally exposes a tagged stream without keep;
  // keep is only needed on the DataMover response side.
  private val valueCfg = Axi4StreamConfig(
    dataWidth = beatBytes,
    destWidth = 6,
    useDest = true,
    useLast = true
  )

  val io = new Bundle {
    val request = slave(Stream(P4AttentionTile(maxContext)))
    val metadataBaseAddress = in UInt(32 bits)
    val valueBaseAddress = in UInt(32 bits)
    val valueOutputTag = in Bits(6 bits)

    val ddrCmd = master(Stream(Bits(72 bits)))
    val ddrData = slave(Axi4Stream(ddrCfg))

    val metadata = master(Stream(Bits(32 bits)))
    val value = master(Axi4Stream(valueCfg))
    val done = master(Flow(P4AttentionTileDone(maxContext)))
    val busy = out Bool()
    val error = out Bool()
    val errorCode = out UInt(4 bits)
  }

  private val idle = U(0, 3 bits)
  private val metadataOwner = U(1, 3 bits)
  private val launchValue = U(2, 3 bits)
  private val valueOwner = U(3, 3 bits)
  private val fault = U(4, 3 bits)
  private val state = Reg(UInt(3 bits)) init idle

  private val requestReg = Reg(P4AttentionTile(maxContext))
  private val errorReg = Reg(Bool()) init False
  private val errorCodeReg = Reg(UInt(4 bits)) init 0

  private val metadataRequester = new P4KvMetadataRequester(
    busWidth = busWidth,
    maxContext = maxContext,
    tileTokens = tileTokens
  )
  private val valueRequester = new P4KvTileRequester(
    busWidth = busWidth,
    maxContext = maxContext,
    tileTokens = tileTokens,
    bytesPerToken = valueBytesPerToken
  )

  // The memory-map contract is fixed for this wrapper: scale/zero lines use
  // stripe tag 2 and K/V value lines use stripe tag 1.
  metadataRequester.io.baseAddress := io.metadataBaseAddress
  metadataRequester.io.commandTag := B"0010"
  valueRequester.io.baseAddress := io.valueBaseAddress
  valueRequester.io.commandTag := B"0001"
  valueRequester.io.outputTag := io.valueOutputTag

  metadataRequester.io.request.valid := io.request.valid && state === idle && !errorReg
  metadataRequester.io.request.payload := io.request.payload
  io.request.ready := state === idle && !errorReg && metadataRequester.io.request.ready

  // Both child requesters see the same captured descriptor.  Only the owner
  // state can make one of these request streams valid.
  valueRequester.io.request.valid := state === launchValue && !errorReg
  valueRequester.io.request.payload := requestReg

  // One command port, with an explicit registered owner.  The requester
  // command payloads are both 72-bit logical commands; physical 40-bit
  // expansion remains the responsibility of the outer DataPath bridge.
  metadataRequester.io.ddrCmd.ready := False
  valueRequester.io.ddrCmd.ready := False
  io.ddrCmd.valid := False
  io.ddrCmd.payload.clearAll()
  when(state === metadataOwner) {
    io.ddrCmd.valid := metadataRequester.io.ddrCmd.valid
    io.ddrCmd.payload := metadataRequester.io.ddrCmd.payload
    metadataRequester.io.ddrCmd.ready := io.ddrCmd.ready
  } elsewhen(state === valueOwner) {
    io.ddrCmd.valid := valueRequester.io.ddrCmd.valid
    io.ddrCmd.payload := valueRequester.io.ddrCmd.payload
    valueRequester.io.ddrCmd.ready := io.ddrCmd.ready
  }

  // One response port, selected solely by the registered owner.  In replay
  // states the child valid is zero, so no DDR command or response is created.
  metadataRequester.io.ddrData.valid := io.ddrData.valid && state === metadataOwner
  metadataRequester.io.ddrData.data := io.ddrData.data
  metadataRequester.io.ddrData.keep := io.ddrData.keep
  metadataRequester.io.ddrData.last := io.ddrData.last
  valueRequester.io.ddrData.valid := io.ddrData.valid && state === valueOwner
  valueRequester.io.ddrData.data := io.ddrData.data
  valueRequester.io.ddrData.keep := io.ddrData.keep
  valueRequester.io.ddrData.last := io.ddrData.last
  io.ddrData.ready := False
  when(state === metadataOwner) {
    io.ddrData.ready := metadataRequester.io.ddrData.ready
  } elsewhen(state === valueOwner) {
    io.ddrData.ready := valueRequester.io.ddrData.ready
  }

  // Metadata and value outputs remain independently backpressurable.  The
  // value output is suppressed during the metadata owner phase, even though
  // its child is already instantiated.
  io.metadata.valid := state === metadataOwner && metadataRequester.io.metadata.valid
  io.metadata.payload := metadataRequester.io.metadata.payload
  metadataRequester.io.metadata.ready := io.metadata.ready && state === metadataOwner

  io.value.valid := state === valueOwner && valueRequester.io.output.valid
  io.value.data := valueRequester.io.output.data
  io.value.dest := valueRequester.io.output.dest
  io.value.last := valueRequester.io.output.last
  valueRequester.io.output.ready := io.value.ready && state === valueOwner

  io.done.valid := state === valueOwner && valueRequester.io.done.valid
  io.done.payload := valueRequester.io.done.payload
  io.busy := state =/= idle
  io.error := errorReg || metadataRequester.io.error || valueRequester.io.error
  io.errorCode := errorCodeReg

  val requestEnd = UInt((contextWidth + 1) bits)
  requestEnd := io.request.payload.startToken.resize(contextWidth + 1) +
    io.request.payload.tokenCount.resize(contextWidth + 1)
  val requestShapeValid = io.request.payload.tokenCount =/= 0 &&
    io.request.payload.tokenCount <= tileTokens && requestEnd <= maxContext

  when(io.request.fire) {
    when(!requestShapeValid) {
      errorReg.set()
      errorCodeReg := U(1, 4 bits)
      state := fault
    } otherwise {
      requestReg := io.request.payload
      state := metadataOwner
    }
  }

  when(state === metadataOwner && metadataRequester.io.done.valid) {
    state := launchValue
  }

  when(state === launchValue && valueRequester.io.request.fire) {
    state := valueOwner
  }

  when(state === valueOwner && valueRequester.io.done.valid) {
    state := idle
  }

  // A child error is terminal for this transaction.  This also converts the
  // child requesters' sticky error flags into a deterministic wrapper fault,
  // preventing a controller from waiting forever for a completion pulse.
  when(!errorReg && (metadataRequester.io.error || valueRequester.io.error)) {
    errorReg.set()
    errorCodeReg := Mux(metadataRequester.io.error, U(2, 4 bits), U(3, 4 bits))
    state := fault
  }

  // A response beat outside the registered owner is a protocol violation.
  // Do not present it to either requester.
  when(io.ddrData.valid && state =/= metadataOwner && state =/= valueOwner) {
    errorReg.set()
    errorCodeReg := U(4, 4 bits)
    state := fault
  }
}

/** Focused elaboration entry point for the standalone fetch frontend. */
object P4KvFetchFrontendTest extends App {
  SpinalConfig(
    targetDirectory = "../kv260/speculative/build/p4i_kv_fetch_frontend_elab",
    oneFilePerComponent = true
  ).generateVerilog(new P4KvFetchFrontend(
    busWidth = 512,
    maxContext = 1024,
    tileTokens = 64,
    valueBytesPerToken = 128
  ))
}
