package attn

import spinal.core._

/** Focused P5 metadata-line elaboration with the production 512-bit bus. */
object KvScaleZeroPackerP5Test extends App {
  SpinalConfig(
    targetDirectory = "../kv260/speculative/build/p5e_metadata_elab",
    oneFilePerComponent = false
  ).generateVerilog(new KvScaleZeroPacker(busWidth = 512, head = 2, layer = 2))
}
