package top

import spinal.core._
import spinal.lib._

import scala.language.postfixOps

/**
  * Converts one AXI-Lite speculative launch into an ordered token stream.
  *
  * A launch snapshots all candidate IDs and K.  The snapshot is held until
  * the downstream consumer accepts every token, so valid/data remain stable
  * while ready is low.  The component deliberately has its own Stream
  * interface; the legacy token Flow is not modified or consumed here.
  */
class SpeculativeTokenIngress(maxK: Int = 4) extends Component {
  require(maxK >= 1 && maxK <= 7, "maxK must fit the 3-bit AXI-Lite K field")

  private val tokenWidth = 16
  private val userWidth = 6
  private val countWidth = log2Up(maxK + 1)

  val io = new Bundle {
    val enable = in Bool()
    val start = in Bool()
    val k = in UInt (countWidth bits)
    val candidateIds = in Vec(Bits(tokenWidth bits), maxK)
    val tokens = master(Stream(util.AxiFrame(Bits(tokenWidth bits), userBit = userWidth)))
    val busy = out Bool()
    val fault = out Bool()
  }

  val busy = Bool().setAsReg().init(False)
  val fault = Bool().setAsReg().init(False)
  val activeK = UInt(countWidth bits).setAsReg().init(0)
  val index = UInt(countWidth bits).setAsReg().init(0)
  val candidateSnapshot = Vec.fill(maxK)(Bits(tokenWidth bits).setAsReg().init(0))

  // A disabled speculative path is electrically quiet and cancels a partial
  // batch.  This is what makes disabling speculation a zero-output operation.
  when(!io.enable) {
    busy.clear()
    index.clearAll()
    activeK.clearAll()
  }

  // start is a pulse from AxiLiteCtrl.  Ignore overlapping launches while a
  // previous sequence is stalled or still draining.
  when(io.start && io.enable) {
    when(!busy && io.k >= 1 && io.k <= maxK) {
      for (i <- 0 until maxK) {
        candidateSnapshot(i) := io.candidateIds(i)
      }
      activeK := io.k
      index.clearAll()
      busy.set()
      fault.clear()
    } otherwise {
      fault.set()
    }
  }

  val tokenData = Bits(tokenWidth bits)
  tokenData := candidateSnapshot(0)
  for (i <- 1 until maxK) {
    when(index === i) {
      tokenData := candidateSnapshot(i)
    }
  }

  io.tokens.valid := busy && io.enable
  io.tokens.tdata := tokenData
  // Keep the legacy token kind in the low nibble and carry candidate q in
  // the two otherwise-unused high bits. GenMem strips q before producing the
  // six-bit routing tag and retains it in an independent transaction register.
  io.tokens.tuser := index.takeLow(2).asBits ## B(2, 4 bits)

  when(io.tokens.fire) {
    when(index === (activeK - 1).resized) {
      busy.clear()
      index.clearAll()
      activeK.clearAll()
    } otherwise {
      index := index + 1
    }
  }

  io.busy := busy
  io.fault := fault
}

object SpeculativeTokenIngressElab extends App {
  SpinalConfig(
    targetDirectory = "../kv260/speculative/build/speculative_token_ingress_elab",
    oneFilePerComponent = false
  ).generateVerilog(new SpeculativeTokenIngress())
}
