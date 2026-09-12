package attn

import spinal.core._
import spinal.lib._

import scala.language.postfixOps

/** Metadata for one bounded speculative attention transaction.
  *
  * The controller is deliberately independent of the arithmetic kernels.  A
  * production adapter owns the tile response and completion events, while
  * QKMul, SerialSafeSoftmax and the V-AXPY engine remain the existing data
  * path.  Keeping the contract in typed Streams/Flows makes the phase and
  * identity checks explicit at the integration boundary.
  */
case class P4AttentionStart(maxContext: Int, maxK: Int = 4) extends Bundle {
  val k = UInt(log2Up(maxK + 1) bits)
  val committedTokens = UInt(log2Up(maxContext + 1) bits)
}

case class P4AttentionTile(maxContext: Int) extends Bundle {
  val phase = Bool()       // false: QK, true: V weighted accumulation
  val source = Bool()      // false: committed DDR, true: tentative buffer
  val buffer = Bool()      // ping/pong tile buffer identity
  val query = UInt(2 bits)
  val startToken = UInt(log2Up(maxContext + 1) bits)
  val tokenCount = UInt(log2Up(maxContext + 1) bits)
  val last = Bool()        // tentative source is the causal tail
}

case class P4AttentionTileDone(maxContext: Int) extends Bundle {
  val phase = Bool()
  val source = Bool()
  val buffer = Bool()
  val query = UInt(2 bits)
  val startToken = UInt(log2Up(maxContext + 1) bits)
}

case class P4AttentionSoftmax(maxContext: Int) extends Bundle {
  val query = UInt(2 bits)
  val tokenCount = UInt(log2Up(maxContext + 1) bits)
}

case class P4AttentionSoftmaxDone() extends Bundle {
  val query = UInt(2 bits)
}

/** P4-B causal phase controller for K=1..4 and 64-token prefix tiles.
  *
  * A tile request is held in ISSUE_TILE until accepted.  Once accepted, its
  * phase/source/query/start identity is latched and the controller advances
  * only on a matching tile_done Flow event.  The same causal ranges are
  * replayed for QK and V.  Softmax is launched only after the QK tail and is
  * given the inclusive visible count committed+q+1.
  */
class P4AttentionPhaseController(
                               maxContext: Int,
                               tileTokens: Int = 64,
                               maxK: Int = 4
                             ) extends Component {
  require(tileTokens > 0 && tileTokens <= maxContext)
  require(maxK >= 1 && maxK <= 4)

  val contextWidth = log2Up(maxContext + 1)

  val io = new Bundle {
    val start = slave(Stream(P4AttentionStart(maxContext, maxK)))
    val tile = master(Stream(P4AttentionTile(maxContext)))
    val tileDone = slave(Flow(P4AttentionTileDone(maxContext)))
    val softmax = master(Stream(P4AttentionSoftmax(maxContext)))
    val softmaxDone = slave(Flow(P4AttentionSoftmaxDone()))
    val completionError = in Bool()
    val queryDone = master(Flow(UInt(2 bits)))
    val busy = out Bool()
    val done = out Bool()
    val error = out Bool()
    val errorCode = out UInt(4 bits)
  }

  val idle = U(0, 3 bits)
  val issueTile = U(1, 3 bits)
  val waitTile = U(2, 3 bits)
  val issueSoftmax = U(3, 3 bits)
  val waitSoftmax = U(4, 3 bits)
  val complete = U(5, 3 bits)
  val fault = U(6, 3 bits)
  val state = Reg(UInt(3 bits)) init idle

  val kReg = Reg(UInt(3 bits)) init 0
  val queryReg = Reg(UInt(2 bits)) init 0
  val committedReg = Reg(UInt(contextWidth bits)) init 0
  val offsetReg = Reg(UInt(contextWidth bits)) init 0
  val phaseReg = Reg(Bool()) init False
  val sourceReg = Reg(Bool()) init False
  val bufferReg = Reg(Bool()) init False

  val inflightPhase = Reg(Bool()) init False
  val inflightSource = Reg(Bool()) init False
  val inflightBuffer = Reg(Bool()) init False
  val inflightQuery = Reg(UInt(2 bits)) init 0
  val inflightStart = Reg(UInt(contextWidth bits)) init 0

  val remaining = UInt(contextWidth bits)
  remaining := committedReg - offsetReg
  val committedCount = UInt(contextWidth bits)
  committedCount := Mux(remaining > tileTokens, U(tileTokens, contextWidth bits), remaining)
  val committedLast = remaining <= tileTokens

  val startK = io.start.payload.k
  val startCommitted = io.start.payload.committedTokens
  val finalTokens = UInt((contextWidth + 1) bits)
  finalTokens := startCommitted.resize(contextWidth + 1) + startK.resize(contextWidth + 1)

  val errK = U(1, 4 bits)
  val errContext = U(2, 4 bits)
  val errTile = U(3, 4 bits)
  val errSoftmax = U(4, 4 bits)
  val errUnexpected = U(5, 4 bits)

  io.start.ready := state === idle
  io.tile.valid := state === issueTile
  io.tile.phase := phaseReg
  io.tile.source := sourceReg
  io.tile.buffer := bufferReg
  io.tile.query := queryReg
  io.tile.startToken := Mux(sourceReg, U(0, contextWidth bits), offsetReg)
  io.tile.tokenCount := Mux(sourceReg, queryReg.resize(contextWidth) + 1, committedCount)
  io.tile.last := sourceReg

  io.softmax.valid := state === issueSoftmax
  io.softmax.query := queryReg
  io.softmax.tokenCount := (committedReg.resize(contextWidth) + queryReg.resize(contextWidth) + 1).resized

  io.queryDone.valid := state === waitTile && io.tileDone.valid && inflightPhase && inflightSource &&
    io.tileDone.phase === inflightPhase && io.tileDone.source === inflightSource &&
    io.tileDone.buffer === inflightBuffer &&
    io.tileDone.query === inflightQuery && io.tileDone.startToken === inflightStart
  io.queryDone.payload := queryReg
  io.busy := (state =/= idle) && (state =/= complete) && (state =/= fault)
  io.done := state === complete
  io.error := state === fault
  val errorCodeReg = Reg(UInt(4 bits)) init 0
  io.errorCode := errorCodeReg

  when(io.completionError) {
    state := fault
    errorCodeReg := errTile
  } elsewhen((io.tileDone.valid && state =/= waitTile) ||
    (io.softmaxDone.valid && state =/= waitSoftmax)) {
    state := fault
    errorCodeReg := errUnexpected
  } otherwise {
    switch(state) {
      is(idle) {
        when(io.start.fire) {
          when(startK < 1 || startK > maxK) {
            state := fault
            errorCodeReg := errK
          } otherwise {
            when(finalTokens > maxContext) {
              state := fault
              errorCodeReg := errContext
            } otherwise {
              kReg := startK
              queryReg.clearAll()
              committedReg := startCommitted
              offsetReg.clearAll()
              phaseReg.clear()
              sourceReg := startCommitted === 0
              bufferReg.clearAll()
              state := issueTile
            }
          }
        }
      }
      is(issueTile) {
        when(io.tile.fire) {
          inflightPhase := phaseReg
          inflightSource := sourceReg
          inflightBuffer := bufferReg
          inflightQuery := queryReg
          inflightStart := Mux(sourceReg, U(0, contextWidth bits), offsetReg)
          state := waitTile
        }
      }
      is(waitTile) {
        when(io.tileDone.valid) {
          when(io.tileDone.phase =/= inflightPhase || io.tileDone.source =/= inflightSource ||
            io.tileDone.buffer =/= inflightBuffer ||
            io.tileDone.query =/= inflightQuery || io.tileDone.startToken =/= inflightStart) {
            state := fault
            errorCodeReg := errTile
          } otherwise {
            bufferReg := !bufferReg
            when(!inflightSource) {
              when(committedLast) {
                sourceReg := True
                offsetReg.clearAll()
              } otherwise {
                offsetReg := offsetReg + tileTokens
              }
              state := issueTile
            } otherwise {
              when(!inflightPhase) {
                state := issueSoftmax
              } otherwise {
                when(queryReg === (kReg - 1)) {
                  state := complete
                } otherwise {
                  queryReg := queryReg + 1
                  phaseReg.clear()
                  offsetReg.clearAll()
                  sourceReg := committedReg === 0
                  state := issueTile
                }
              }
            }
          }
        }
      }
      is(issueSoftmax) {
        when(io.softmax.fire) {
          state := waitSoftmax
        }
      }
      is(waitSoftmax) {
        when(io.softmaxDone.valid) {
          when(io.softmaxDone.query =/= queryReg) {
            state := fault
            errorCodeReg := errSoftmax
          } otherwise {
            phaseReg := True
            offsetReg.clearAll()
            sourceReg := committedReg === 0
            state := issueTile
          }
        }
      }
      is(complete) {
        state := idle
      }
      is(fault) {
        state := fault
      }
    }
  }
}

/** Completion adapter for the real production streams.
  *
  * QKMul exposes one reduced score as a Flow event, so the adapter counts
  * exactly the accepted tile's tokenCount events before returning tile_done.
  * The V side is a Flow with a required Fragment.last boundary; no V tile
  * completion can be manufactured from a single valid pulse.  Softmax uses
  * its actual output last marker.  All returned metadata is copied from the
  * accepted tile/softmax request, including ping/pong buffer identity.
  */
class P4AttentionCompletionAdapter(maxContext: Int) extends Component {
  val contextWidth = log2Up(maxContext + 1)

  val io = new Bundle {
    val tileAccepted = in Bool()
    val tile = in(P4AttentionTile(maxContext))
    val qkScoreValid = in Bool()
    val softmaxAccepted = in Bool()
    val softmaxQuery = in UInt(2 bits)
    val softmaxOutputValid = in Bool()
    val softmaxOutputLast = in Bool()
    // One V-AXPY reduction result per tile token.  The production
    // MulAddSG scalar output is a Flow (not a ready/valid Stream), so its
    // Fragment.last boundary is the completion contract here.
    val vAxpyTileOut = slave(Flow(Fragment(util.AxiFrame(Bits(16 bits), userBit = 6))))
    val tileDone = master(Flow(P4AttentionTileDone(maxContext)))
    val softmaxDone = master(Flow(P4AttentionSoftmaxDone()))
    val error = out Bool()
    val errorCode = out UInt(4 bits)
  }

  val tileActive = Reg(Bool()) init False
  val tilePhase = Reg(Bool()) init False
  val tileSource = Reg(Bool()) init False
  val tileBuffer = Reg(Bool()) init False
  val tileQuery = Reg(UInt(2 bits)) init 0
  val tileStart = Reg(UInt(contextWidth bits)) init 0
  val tileCount = Reg(UInt(contextWidth bits)) init 0
  val elementCount = Reg(UInt(contextWidth bits)) init 0

  val softmaxActive = Reg(Bool()) init False
  val softmaxQueryReg = Reg(UInt(2 bits)) init 0
  val errorReg = Reg(Bool()) init False
  val errorCodeReg = Reg(UInt(4 bits)) init 0

  val qkLast = tileCount =/= 0 && elementCount === (tileCount - 1)
  val qkDone = tileActive && !tilePhase && io.qkScoreValid && qkLast

  val vFire = tileActive && tilePhase && io.vAxpyTileOut.valid && !errorReg
  val vLastExpected = tileCount =/= 0 && elementCount === (tileCount - 1)
  val vDone = vFire && tileActive && tilePhase && vLastExpected && io.vAxpyTileOut.last
  val vBadBoundary = vFire && tileActive && tilePhase &&
    (io.vAxpyTileOut.last =/= vLastExpected)

  io.tileDone.valid := qkDone || vDone
  io.tileDone.phase := tilePhase
  io.tileDone.source := tileSource
  io.tileDone.buffer := tileBuffer
  io.tileDone.query := tileQuery
  io.tileDone.startToken := tileStart

  io.softmaxDone.valid := softmaxActive && io.softmaxOutputValid && io.softmaxOutputLast
  io.softmaxDone.query := softmaxQueryReg
  io.error := errorReg
  io.errorCode := errorCodeReg

  when(io.tileAccepted) {
    tileActive := True
    tilePhase := io.tile.phase
    tileSource := io.tile.source
    tileBuffer := io.tile.buffer
    tileQuery := io.tile.query
    tileStart := io.tile.startToken
    tileCount := io.tile.tokenCount
    elementCount.clearAll()
  }

  when(tileActive && !tilePhase && io.qkScoreValid) {
    when(qkLast) {
      tileActive.clear()
      elementCount.clearAll()
    } otherwise {
      elementCount := elementCount + 1
    }
  }

  when(vFire) {
    when(vDone) {
      tileActive.clear()
      elementCount.clearAll()
    } elsewhen(vBadBoundary) {
      tileActive.clear()
      errorReg.set()
      errorCodeReg := U(3, 4 bits)
    } otherwise {
      elementCount := elementCount + 1
    }
  }

  when(io.softmaxAccepted) {
    softmaxActive := True
    softmaxQueryReg := io.softmaxQuery
  }
  when(io.softmaxDone.valid) {
    softmaxActive.clear()
  }

  // An output last without an accepted request is an unsolicited completion.
  when(io.softmaxOutputValid && io.softmaxOutputLast && !softmaxActive) {
    errorReg.set()
    errorCodeReg := U(5, 4 bits)
  }
}

/** Lightweight elaboration entry point used by the focused P4-B check. */
object P4AttentionPhaseControllerTest extends App {
  SpinalVerilog(new P4AttentionPhaseController(maxContext = 4096, tileTokens = 64, maxK = 4))
  SpinalVerilog(new P4AttentionCompletionAdapter(maxContext = 4096))
}
