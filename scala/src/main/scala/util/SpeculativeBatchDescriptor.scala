package util

import spinal.core._

/**
  * Small, clock-domain-local contract for a production speculative batch.
  * mode=0 is GEMV and mode=1 is GEMM. K is encoded as an unsigned value in
  * the architected range 1..4; the consumer validates it at the handshake.
  */
case class SpeculativeBatchDescriptor() extends Bundle {
  val mode = Bool()
  val k = UInt(3 bits)
  val projectionTag = Bits(6 bits)
  val layerId = UInt(8 bits)
  val rows = UInt(16 bits)
  val beatsPerRow = UInt(16 bits)
}
