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
    io.tileDone.query === inflightQuery && io.tileDone.startToken === inflightStart
  io.queryDone.payload := queryReg
  io.busy := (state =/= idle) && (state =/= complete) && (state =/= fault)
  io.done := state === complete
  io.error := state === fault
  val errorCodeReg = Reg(UInt(4 bits)) init 0
  io.errorCode := errorCodeReg

  when((io.tileDone.valid && state =/= waitTile) ||
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
          inflightQuery := queryReg
          inflightStart := Mux(sourceReg, U(0, contextWidth bits), offsetReg)
          state := waitTile
        }
      }
      is(waitTile) {
        when(io.tileDone.valid) {
          when(io.tileDone.phase =/= inflightPhase || io.tileDone.source =/= inflightSource ||
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

/** Lightweight elaboration entry point used by the focused P4-B check. */
object P4AttentionPhaseControllerTest extends App {
  SpinalVerilog(new P4AttentionPhaseController(maxContext = 4096, tileTokens = 64, maxK = 4))
}
