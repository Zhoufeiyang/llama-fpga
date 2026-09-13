package top

import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axilite.{AxiLite4, AxiLite4SlaveFactory, AxiLite4SpecRenamer}

import scala.language.postfixOps

class AxiLiteCtrl(resetLowPolarity: Boolean = true) extends Component {

  val io = new Bundle {
    val ctrl = slave(AxiLite4(32, 32))
    val tokenIndex = master(Flow(util.AxiFrame(Bits(16 bits), userBit = 6)))
    // Independent, backpressurable speculative token path.  The legacy
    // tokenIndex Flow above remains unchanged for non-speculative traffic.
    val speculativeTokenIndex = master(Stream(util.AxiFrame(Bits(16 bits), userBit = 6)))
    val cmdSel = out UInt (2 bits)
    val presetLayer = out Bits(5 bits)
    val presetToken = out Bits(10 bits)
    val speculativeEnable = out Bool()
    val speculativeActive = out Bool()
    val speculativeQuery = out UInt(2 bits)
    // Committed length spans 0..1024 inclusive; physical positions remain
    // 0..1023 and are checked before any candidate launch.
    val speculativeCommitted = out UInt(11 bits)
    val speculativeBase = out UInt(11 bits)
    val speculativeK = out UInt(3 bits)
    val speculativeEpoch = out UInt(8 bits)
    val perfWindowActive = out Bool()
    val perfWindowClear = out Bool()
    // Production speculative batch descriptor.  It is held until the
    // command generator accepts it, so AXI-Lite writes cannot be lost while
    // the data path is busy.
    val speculativeBatch = master(Stream(util.SpeculativeBatchDescriptor()))

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
    val projectionDone = in Bool() addTag (crossClockDomain)
    val projectionError = in Bool() addTag (crossClockDomain)
    val perfWeightBytes = in UInt(64 bits) addTag (crossClockDomain)
    val perfKvReadBytes = in UInt(64 bits) addTag (crossClockDomain)
    val perfKvWriteBytes = in UInt(64 bits) addTag (crossClockDomain)
    val perfVerifyCycles = in UInt(64 bits) addTag (crossClockDomain)
    val perfMemoryStallCycles = in UInt(64 bits) addTag (crossClockDomain)
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
  val speculativeCommitted = UInt(11 bits).setAsReg().init(0)
  val speculativeBatchK = UInt(3 bits).setAsReg().init(1)
  val speculativeStart = Bool().setAsReg().init(False)
  val speculativeCommit = Bool().setAsReg().init(False)
  val speculativeCommitDelta = UInt(3 bits).setAsReg().init(0)
  val speculativeRollback = Bool().setAsReg().init(False)
  val speculativeActive = Bool().setAsReg().init(False)
  val speculativeFault = Bool().setAsReg().init(False)
  val speculativeBase = UInt(11 bits).setAsReg().init(0)
  val speculativePointer = UInt(11 bits).setAsReg().init(0)
  val speculativeEpoch = UInt(8 bits).setAsReg().init(0)
  val candidateIds = Vec.fill(4)(Bits(16 bits).setAsReg().init(0))
  val initialTargetId = Bits(16 bits).setAsReg().init(0)
  val targetIds = Vec.fill(5)(Bits(16 bits).setAsReg().init(0))
  val resultCount = UInt(3 bits).setAsReg().init(0)
  val resultAck = Bool().setAsReg().init(False)

  // Descriptor register window.  The launch bit is a pulse; all descriptor
  // fields are registered and remain stable until descriptor.valid && ready.
  val descriptorMode = Bool().setAsReg().init(False)
  val descriptorK = UInt(3 bits).setAsReg().init(1)
  val descriptorProjectionTag = Bits(6 bits).setAsReg().init(0)
  val descriptorLayerId = UInt(8 bits).setAsReg().init(0)
  val descriptorRows = UInt(16 bits).setAsReg().init(0)
  val descriptorBeatsPerRow = UInt(16 bits).setAsReg().init(0)
  val descriptorLaunch = Bool().setAsReg().init(False)
  val descriptorPending = Bool().setAsReg().init(False)
  val descriptorFault = Bool().setAsReg().init(False)
  val projectionDoneSeen = Bool().setAsReg().init(False)
  val projectionErrorSeen = Bool().setAsReg().init(False)

  ctrl.write(token, 0x00, 0)
  ctrl.write(tokenVld, 0x00, 16)
  ctrl.write(isPrefillToken, 0x00, 17)
  ctrl.write(isPrefillLastToken, 0x00, 18)
  ctrl.write(isDecodeToken, 0x00, 19)
  ctrl.write(cmdSel, 0x24, 0)
  // P4 runtime window. 0x28[0] selects speculative attention,
  // [3:2] is candidate q (0..3), and [26:16] is the committed KV length.
  ctrl.write(speculativeEnable, 0x28, 0)
  ctrl.write(speculativeQuery, 0x28, 2)
  ctrl.write(speculativeCommitted, 0x28, 16)
  // P5 pointer-commit control plane. Candidate KV occupies the future slots
  // [SPEC_BASE_POS, SPEC_BASE_POS+K); only COMMIT_DELTA advances visibility.
  ctrl.write(speculativeStart, 0x100, 0)
  ctrl.write(speculativeBatchK, 0x104, 0)
  ctrl.write(speculativeCommitted, 0x108, 0)
  ctrl.read(speculativeCommitted, 0x108, 0)
  ctrl.read(speculativeBase, 0x10C, 0)
  ctrl.write(speculativeCommitDelta, 0x120, 0)
  ctrl.write(speculativeCommit, 0x120, 8)
  ctrl.write(speculativeRollback, 0x124, 0)
  for (i <- 0 until 4) {
    ctrl.write(candidateIds(i), 0x110 + i * 4, 0)
    ctrl.read(candidateIds(i), 0x110 + i * 4, 0)
  }
  ctrl.read(resultCount, 0x134, 0)
  ctrl.write(initialTargetId, 0x138, 0)
  ctrl.read(initialTargetId, 0x138, 0)
  for (i <- 0 until 5) ctrl.read(targetIds(i), 0x140 + i * 4, 0)
  ctrl.write(resultAck, 0x154, 0)
  ctrl.write(softReset, 0xC0, 0)

  ctrl.write(descriptorMode, 0x160, 0)
  ctrl.write(descriptorK, 0x160, 8)
  ctrl.write(descriptorProjectionTag, 0x164, 0)
  ctrl.write(descriptorLayerId, 0x168, 0)
  ctrl.write(descriptorRows, 0x16C, 0)
  ctrl.write(descriptorBeatsPerRow, 0x170, 0)
  ctrl.write(descriptorLaunch, 0x174, 0)

  tokenVld.clear()
  softReset.clear()
  speculativeStart.clear()
  speculativeCommit.clear()
  speculativeRollback.clear()
  resultAck.clear()
  descriptorLaunch.clear()

  io.speculativeBatch.valid := descriptorPending
  io.speculativeBatch.payload.mode := descriptorMode
  io.speculativeBatch.payload.k := descriptorK
  io.speculativeBatch.payload.projectionTag := descriptorProjectionTag
  io.speculativeBatch.payload.layerId := descriptorLayerId
  io.speculativeBatch.payload.rows := descriptorRows
  io.speculativeBatch.payload.beatsPerRow := descriptorBeatsPerRow

  val descriptorShapeValid = descriptorK >= 1 && descriptorK <= 4 &&
    descriptorRows =/= 0 && descriptorBeatsPerRow =/= 0 &&
    // K is a logical candidate dimension, not a physical DMA row count.
    // Keep the AXI-Lite launch check aligned with GenMem so LM-head K=4 is
    // accepted instead of being rejected by rows*K overflow.
    descriptorRows <= 65535
  when(descriptorLaunch) {
    when(!descriptorPending && descriptorShapeValid) {
      descriptorPending.set()
      descriptorFault.clear()
      projectionDoneSeen.clear()
      projectionErrorSeen.clear()
    } otherwise {
      descriptorFault.set()
    }
  }
  when(io.speculativeBatch.fire) {
    descriptorPending.clear()
  }
  projectionDoneSeen.setWhen(status.projectionDone)
  projectionErrorSeen.setWhen(status.projectionError)

  val descriptorStatus = Bits(32 bits)
  descriptorStatus.clearAll()
  descriptorStatus(0) := io.speculativeBatch.valid
  descriptorStatus(1) := io.speculativeBatch.ready
  descriptorStatus(2) := projectionDoneSeen
  descriptorStatus(3) := projectionErrorSeen || descriptorFault
  ctrl.read(descriptorStatus, 0x17C, 0)
  ctrl.read(status.perfWeightBytes(31 downto 0), 0x180, 0)
  ctrl.read(status.perfKvReadBytes(31 downto 0), 0x184, 0)
  ctrl.read(status.perfKvWriteBytes(31 downto 0), 0x188, 0)
  ctrl.read(status.perfVerifyCycles(31 downto 0), 0x18C, 0)
  ctrl.read(status.perfMemoryStallCycles(31 downto 0), 0x190, 0)

  val speculativeEnd = speculativeCommitted.resize(12) + speculativeBatchK.resize(12)
  val speculativeStartValid = !speculativeActive && resultCount === 0 &&
    speculativeBatchK >= 1 && speculativeBatchK <= 4 && speculativeEnd <= 1024
  when(speculativeStart) {
    when(speculativeStartValid) {
      speculativeBase := speculativeCommitted
      speculativePointer := speculativeEnd.resized
      speculativeEpoch := speculativeEpoch + 1
      speculativeActive.set()
      speculativeFault.clear()
      // g[0] exists before the drafted candidates are launched. Seed it
      // atomically so the following K target passes complete g[1..K].
      targetIds(0) := initialTargetId
      resultCount := 1
    } otherwise {
      speculativeFault.set()
    }
  }
  when(speculativeCommit) {
    // A pointer may become visible only after the complete g[0..K] result
    // block proves that every candidate reached the target-pass terminal.
    when(speculativeActive && resultCount === (speculativeBatchK + 1).resized &&
      speculativeCommitDelta <= speculativeBatchK) {
      speculativeCommitted := speculativeBase + speculativeCommitDelta.resized
      speculativePointer := speculativeBase + speculativeCommitDelta.resized
      speculativeActive.clear()
    } otherwise {
      speculativeFault.set()
    }
  }
  when(speculativeRollback) {
    when(speculativeActive) {
      speculativePointer := speculativeCommitted
      speculativeActive.clear()
    } otherwise {
      speculativeFault.set()
    }
  }
  when(resultAck && !speculativeActive) {
    resultCount.clearAll()
    for (i <- 0 until 5) targetIds(i).clearAll()
  }

  val speculativeStatus = Bits(32 bits)
  speculativeStatus.clearAll()
  speculativeStatus(0) := !speculativeActive
  speculativeStatus(1) := speculativeActive
  speculativeStatus(2) := speculativeFault
  speculativeStatus(3) := speculativeActive &&
    resultCount === (speculativeBatchK + 1).resized
  speculativeStatus(26 downto 16) := speculativePointer.asBits
  ctrl.read(speculativeStatus, 0x130, 0)

  // Candidate IDs are snapshotted only after the existing pointer-control
  // launch has passed validation.  The ingress owns the sequence state and
  // may therefore hold valid high while a downstream core applies backpressure.
  val speculativeTokenIngress = new SpeculativeTokenIngress(maxK = 4)
  speculativeTokenIngress.io.enable := speculativeEnable
  speculativeTokenIngress.io.start := speculativeStart && speculativeStartValid
  speculativeTokenIngress.io.k := speculativeBatchK
  for (i <- 0 until 4) {
    speculativeTokenIngress.io.candidateIds(i) := candidateIds(i)
  }
  io.speculativeTokenIndex.valid := speculativeTokenIngress.io.tokens.valid
  io.speculativeTokenIndex.tdata := speculativeTokenIngress.io.tokens.tdata
  io.speculativeTokenIndex.tuser := speculativeTokenIngress.io.tokens.tuser
  speculativeTokenIngress.io.tokens.ready := io.speculativeTokenIndex.ready

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

  // Preserve every verification-position prediction until PS explicitly
  // acknowledges the result block. This prevents a new launch from silently
  // overwriting g[0..K].
  when(argMaxVldDly && speculativeActive) {
    when(resultCount < 5) {
      targetIds(resultCount) := argMaxIndexDly.resized
      resultCount := resultCount + 1
    } otherwise {
      speculativeFault.set()
    }
  }

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
  io.presetLayer := 0
  io.presetToken := 0
  io.speculativeEnable := speculativeEnable
  io.speculativeActive := speculativeActive
  io.speculativeQuery := speculativeQuery
  io.speculativeCommitted := speculativeCommitted
  io.speculativeBase := speculativeBase
  io.speculativeK := speculativeBatchK
  io.speculativeEpoch := speculativeEpoch
  io.perfWindowActive := speculativeActive &&
    resultCount < (speculativeBatchK + 1).resized
  io.perfWindowClear := speculativeStart && speculativeStartValid


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
