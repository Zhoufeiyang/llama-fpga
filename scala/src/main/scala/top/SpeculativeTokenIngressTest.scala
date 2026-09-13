package top

import spinal.core._

/** Focused elaboration entry point; simulation is driven by the checked-in SV TB. */
object SpeculativeTokenIngressTest extends App {
  SpinalConfig(
    targetDirectory = "../kv260/speculative/build/speculative_token_ingress_elab",
    oneFilePerComponent = false
  ).generateVerilog(new SpeculativeTokenIngress())
}
