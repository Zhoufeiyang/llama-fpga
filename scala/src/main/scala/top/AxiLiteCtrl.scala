package top

import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axilite.{AxiLite4, AxiLite4SlaveFactory, AxiLite4SpecRenamer}

import scala.language.postfixOps

class AxiLiteCtrl(resetLowPolarity: Boolean = true) extends Component {

  val io = new Bundle {
    val ctrl = slave(AxiLite4(32, 32))
    val tokenIndex = master(Flow(util.AxiFrame(Bits(16 bits), userBit = 6)))
    val cmdSel = out UInt (2 bits)
    val presetLayer = out Bits(5 bits)
    val presetToken = out Bits(10 bits)
    val speculativeEnable = out Bool()
    val speculativeQuery = out UInt(2 bits)
    val speculativeCommitted = out UInt(10 bits)

    //    val attnQKVSplit = out UInt (4 bits) addTag (crossClockDomain)
    //    val attnOSplit = out UInt (4 bits) addTag (crossClockDomain)
    //    val mlpDenseGSplit = out UInt (4 bits) addTag (crossClockDomain)
    //    val lgSplit = out UInt (4 bits) addTag (crossClockDomain)
  }

  val status = new Bundle {
    val tokenCnt = in Bits (16 bits) addTag (crossClockDomain)
    val layerCnt = in Bits (8 bits) addTag (crossClockDomain)
    val argMaxVld = in Bool() addTag (crossClockDomain)
    val argMaxIndex = in Bits (16 bits) addTag (crossClockDomain)
    val prefill = in Bool() addTag (crossClockDomain)
  }

  val liteBus = AxiLite4(32, 32)
  liteBus << io.ctrl
  liteBus.ar.addr.removeDataAssignments()
  liteBus.ar.addr := (B(0, 22 bits) ## io.ctrl.ar.addr.take(10)).asUInt
  liteBus.aw.addr.removeDataAssignments()
  liteBus.aw.addr := (B(0, 22 bits) ## io.ctrl.aw.addr.take(10)).asUInt

  val ctrl = new AxiLite4SlaveFactory(liteBus)

  val token = Bits(16 bits).setAsReg().init(0)
  val tokenVld = Bool().setAsReg().init(False)
  val isPrefillToken = Bool().setAsReg().init(False)
  val isPrefillLastToken = Bool().setAsReg().init(False)
  val isDecodeToken = Bool().setAsReg().init(False)
  val destTokenCnt = Bits(16 bits).setAsReg().init(0)
  val cmdSel = Bits(2 bits).setAsReg().init(0)
  val softReset = Bool().setAsReg().init(False)
  val speculativeEnable = Bool().setAsReg().init(False)
  val speculativeQuery = UInt(2 bits).setAsReg().init(0)
  val speculativeCommitted = UInt(10 bits).setAsReg().init(0)

  ctrl.write(token, 0x00, 0)
  ctrl.write(tokenVld, 0x00, 16)
  ctrl.write(isPrefillToken, 0x00, 17)
  ctrl.write(isPrefillLastToken, 0x00, 18)
  ctrl.write(isDecodeToken, 0x00, 19)
  ctrl.write(cmdSel, 0x24, 0)
  // P4 runtime window. 0x28[0] selects speculative attention,
  // [3:2] is candidate q (0..3), and [25:16] is the committed KV length.
  ctrl.write(speculativeEnable, 0x28, 0)
  ctrl.write(speculativeQuery, 0x28, 2)
  ctrl.write(speculativeCommitted, 0x28, 16)
  ctrl.write(softReset, 0xC0, 0)

  tokenVld.clear()
  softReset.clear()

  val resetCycle = 4
  val resetCnt = UInt(log2Up(resetCycle) bits).setAsReg().init(0)
  val resetCntOvf = resetCnt === resetCycle - 1
  val resetKeep = Bool().setAsReg().init(False)
  resetKeep.setWhen(softReset)
  when(resetKeep) {
    resetCnt := resetCnt + 1
    when(resetCntOvf) {
      resetCnt.clearAll()
      resetKeep.clear()
    }
  }

  val resetDly = Delay(resetKeep, 64, init = False)
  val resetOut = out Bool()
  if (resetLowPolarity) {
    resetOut := ~resetDly
  }
  else {
    resetOut := resetDly
  }

  val tokenCntDly = Delay(status.tokenCnt, init = B(0, 16 bits), cycleCount = 2)
  val argMaxVldDly = Delay(status.argMaxVld, init = False, cycleCount = 2)
  val argMaxIndexDly = Delay(status.argMaxIndex.resize(15), init = B(0, 15 bits), cycleCount = 2)
  val prefillDly = Delay(status.prefill, init = False, cycleCount = 2)
  val layerCntDly = Delay(status.layerCnt, init = B(0, 8 bits), cycleCount = 2)

  val argMaxVldClr = Bool().setAsReg().init(False)
  ctrl.write(argMaxVldClr, 0x80, 0)
  argMaxVldClr.clear()

  val argMaxVldLock = Bool().setAsReg().init(False)
  val argMaxIndexLock = Bits(15 bits).setAsReg().init(0)
  argMaxVldLock.setWhen(argMaxVldDly)
  argMaxVldLock.clearWhen(argMaxVldClr)
  when(argMaxVldDly) {
    argMaxIndexLock := argMaxIndexDly
  }
  when(argMaxVldClr) {
    argMaxIndexLock.clearAll()
  }

  val testReg = UInt(32 bits).setAsReg().init(0)
  val magicNum = UInt(32 bits)
  magicNum := 77

  ctrl.read(tokenCntDly, 0x04, 0)
  ctrl.read(argMaxIndexLock, 0x04, 16)
  ctrl.read(argMaxVldLock, 0x04, 31)

  ctrl.read(prefillDly, 0x08, 0)
  ctrl.read(layerCntDly, 0x20, 0)

  ctrl.write(testReg, 0x0C, 0)
  ctrl.read(testReg, 0x0C, 0)
  ctrl.read(magicNum, 0x44, 0)

  val timer = UInt(32 bits).setAsReg().init(0)
  val flag = Bool().setAsReg().init(False)
  when(flag) {
    timer := timer + 1
  }
  flag.setWhen(tokenVld)
  flag.clearWhen(destTokenCnt === status.tokenCnt)

  ctrl.write(destTokenCnt, 0x10, 0)
  ctrl.read(timer, 0x10, 0)

  val tokenUseTag = Bits(6 bits)
  tokenUseTag.clearAll()
  when(isPrefillToken)(tokenUseTag := 0)
  when(isPrefillLastToken)(tokenUseTag := 1)
  when(isDecodeToken)(tokenUseTag := 2)

  io.tokenIndex.tdata := token
  io.tokenIndex.tuser := tokenUseTag
  io.tokenIndex.valid := tokenVld
  io.cmdSel := cmdSel.asUInt
  io.speculativeEnable := speculativeEnable
  io.speculativeQuery := speculativeQuery
  io.speculativeCommitted := speculativeCommitted


  //  val attnQKVSplit = UInt(4 bits).setAsReg().init(0)
  //  val attnOSplit = UInt(4 bits).setAsReg().init(1)
  //  val mlpDenseGSplit = UInt(4 bits).setAsReg().init(2)
  //  val lgSplit = UInt(4 bits).setAsReg().init(4)
  //  ctrl.write(attnQKVSplit, 0x20, 0)
  //  ctrl.write(attnOSplit, 0x20, 4)
  //  ctrl.write(mlpDenseGSplit, 0x20, 8)
  //  ctrl.write(lgSplit, 0x20, 12)
  //  io.attnQKVSplit := attnQKVSplit
  //  io.attnOSplit := attnOSplit
  //  io.mlpDenseGSplit := mlpDenseGSplit
  //  io.lgSplit := lgSplit
}
