package util

import spinal.core._
import spinal.lib._

import scala.language.postfixOps

/** Retire one logical split-DMA command only after every HP lane responds. */
class SplitDmaStatusJoin(split: Int) extends Component {
  require(split > 1 && isPow2(split))
  val io = new Bundle {
    val physical = Vec(slave(Stream(Bits(8 bits))), split)
    val logical = master(Stream(Bits(8 bits)))
  }
  val joined = StreamJoin(io.physical.map(_.toEvent()))
  io.logical.arbitrationFrom(joined)
  io.logical.payload := io.physical.map(_.payload).reduce(_ | _)
}

object SplitDmaStatusJoinTest extends App {
  SpinalConfig(
    targetDirectory = "../kv260/speculative/build/p5h_split_status_join_elab",
    oneFilePerComponent = true
  ).generateVerilog(new SplitDmaStatusJoin(4))
}
