package top

import spinal.core._

/** Focused P5 elaboration entry point for the production AXI-Lite control map. */
object AxiLiteCtrlP5Test extends App {
  SpinalConfig(
    targetDirectory = "../kv260/speculative/build/p5b_axilite_elab",
    oneFilePerComponent = false
  ).generateVerilog(new AxiLiteCtrl())
}
