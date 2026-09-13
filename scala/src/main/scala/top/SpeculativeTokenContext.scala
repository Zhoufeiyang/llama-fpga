package top

import spinal.core._

import scala.language.postfixOps

/**
  * Separates candidate-position metadata from the legacy six-bit routing tag.
  *
  * SpeculativeTokenIngress encodes q in user[5:4] and the existing token kind
  * in user[3:0].  The command generator accepts one token only when it starts
  * that token's transaction; at that boundary this block freezes q until the
  * next accepted token.  Consequently a queued q+1 cannot change attention or
  * tentative-KV addressing while q is still executing.
  */
class SpeculativeTokenContext extends Component {
  val io = new Bundle {
    val accepted = in Bool()
    val speculativeEnable = in Bool()
    val tokenUser = in Bits(6 bits)
    val routeTag = out Bits(6 bits)
    val acceptedQuery = out UInt(2 bits)
    val activeQuery = out UInt(2 bits)
  }

  val activeQuery = UInt(2 bits).setAsReg().init(0)
  val acceptedQuery = io.tokenUser(5 downto 4).asUInt
  when(io.accepted && io.speculativeEnable) {
    activeQuery := acceptedQuery
  }

  io.routeTag := io.tokenUser.takeLow(4).resize(6)
  io.acceptedQuery := acceptedQuery
  io.activeQuery := activeQuery
}

object SpeculativeTokenContextTest extends App {
  SpinalConfig(
    targetDirectory = "../kv260/speculative/build/speculative_token_context_elab",
    oneFilePerComponent = false
  ).generateVerilog(new SpeculativeTokenContext)
}
