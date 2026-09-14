package top

import spinal.core._
import spinal.lib._

import scala.language.postfixOps

/** Retires a projection only at the real arithmetic output terminal. */
class SpeculativeProjectionRetirement(bankLen: Int = 128, maxK: Int = 4) extends Component {
  require(bankLen > 0)
  val io = new Bundle {
    val launch = slave(Flow(util.SpeculativeBatchDescriptor()))
    val scalarValid = in Bool()
    val scalarTag = in Bits(6 bits)
    val vectorValid = in Bool()
    val vectorTag = in Bits(6 bits)
    val done = master(Flow(SpeculativeProjectionCompletion()))
    val active = out Bool()
    val error = out Bool()
  }

  val activeReg = Reg(Bool()) init False
  val errorReg = Reg(Bool()) init False
  val tagReg = Reg(Bits(6 bits)) init 0
  val layerReg = Reg(UInt(8 bits)) init 0
  val outputTagReg = Reg(Bits(6 bits)) init 0
  val expectedReg = Reg(UInt(18 bits)) init 1
  val countReg = Reg(UInt(18 bits)) init 0
  val vectorModeReg = Reg(Bool()) init False

  val mappedOutputTag = Bits(6 bits)
  val mappedVectorMode = Bool()
  mappedOutputTag := 0
  mappedVectorMode := False
  switch(io.launch.payload.projectionTag) {
    is(4)  { mappedOutputTag := 5 }
    is(5)  { mappedOutputTag := 6 }
    is(7)  { mappedOutputTag := 7 }
    is(11) { mappedOutputTag := 16 }
    is(15) { mappedOutputTag := 22 }
    is(16) { mappedOutputTag := 24 }
    is(17) { mappedOutputTag := 31; mappedVectorMode := True }
    is(19) { mappedOutputTag := 35 }
  }

  val scalarHit = io.scalarValid && !vectorModeReg && io.scalarTag === outputTagReg
  val vectorHit = io.vectorValid && vectorModeReg && io.vectorTag === outputTagReg
  val outputHit = scalarHit || vectorHit

  io.done.valid := False
  io.done.payload.projectionTag := tagReg
  io.done.payload.layerId := layerReg
  io.active := activeReg
  io.error := errorReg

  when(io.launch.valid) {
    when(activeReg || io.launch.payload.k < 1 || io.launch.payload.k > maxK) {
      errorReg.set()
    } otherwise {
      activeReg.set()
      errorReg.clear()
      tagReg := io.launch.payload.projectionTag
      layerReg := io.launch.payload.layerId
      outputTagReg := mappedOutputTag
      vectorModeReg := mappedVectorMode
      countReg.clearAll()
      when(mappedVectorMode) {
        expectedReg := ((io.launch.payload.rows.resize(18) / bankLen) *
          io.launch.payload.k.resize(18)).resized
      } otherwise {
        expectedReg := (io.launch.payload.rows.resize(18) *
          io.launch.payload.k.resize(18)).resized
      }
    }
  }

  when(activeReg && outputHit) {
    when(countReg === expectedReg - 1) {
      io.done.valid := True
      activeReg.clear()
      countReg.clearAll()
    } otherwise {
      countReg := countReg + 1
    }
  }
}

object SpeculativeProjectionRetirementTest extends App {
  SpinalConfig(
    targetDirectory = "../kv260/speculative/build/p3f_projection_retirement_elab",
    oneFilePerComponent = false
  ).generateVerilog(new SpeculativeProjectionRetirement(bankLen = 4))
}
