package top

import spinal.core._
import spinal.lib._

import scala.language.postfixOps

class StateGen(
                busInVecCnt: Int,
                engineOutVecCnt: Int,
                dotOutVecCnt: Int,
                layer: Int,
                head: Int,
                numOfCore: Int,
                maxToken: Int,
                attnVParamTag: Int,
                mlpDParamTag: Int,
                lmHeadParamTag: Int,
                tokenTag: (Int, Int, Int),
                mlpTensorTag: Int,
                vTensorTag: (Int, Int)
              ) extends Component {

  val io = new Bundle {
    val busIn = slave(Flow(Fragment(Bits(6 bits))))
    val engineOut = slave(Flow(Bits(6 bits)))
    val dotOut = slave(Flow(Bits(6 bits)))
    val gtCnt = slave(Flow(Bits(16 bits)))
    val tokenIndexFlow = slave(Flow(Bits(6 bits)))
    // Command-generator completion is passed through this existing state
    // boundary so the top-level control plane has one status owner.
    val projectionDone = in Bool()
    val projectionError = in Bool()
  }

  val status = new Bundle {
    val argmaxVld = in Bool()
    val endOfDecode = in Bool()

    val enPredictor = out Bool()
    val enProSparse = out Bool()

    val tokenNextHit = out Bool()
    val mlpNextHit = out Bool()
    val vNextHit = out Bool()

    val prefill = out Bool()
    val flushRes = out Bool()
    val nextLayer = out Bool()
    val nonZeroCnt = out Bits (16 bits)
    val logitsGen = out Bool()
    val toLogitsGen = out Bool()
    val token = out UInt (log2Up(maxToken) bits)
    val layerCnt = out UInt (log2Up(layer) bits)
    val projectionDone = out Bool()
    val projectionError = out Bool()
  }

  status.enPredictor.clear()
  status.enProSparse.clear()

  val busAlign = new Bundle {
    val prefillIn = Stream(Bool())
    prefillIn.valid := io.tokenIndexFlow.valid
    prefillIn.payload := io.tokenIndexFlow.payload === 0
    val prefillInLock = prefillIn.queue(64, forFMax = true)

    val prefillLastHit = io.busIn.fragment === tokenTag._2
    val prefillHit = io.busIn.fragment === tokenTag._1 || prefillLastHit
    val attnVHit = io.busIn.fragment === attnVParamTag
    val mlpDHit = io.busIn.fragment === mlpDParamTag
    val lmHeadHit = io.busIn.fragment === lmHeadParamTag

    val headCnt = UInt(log2Up(head / numOfCore) bits).setAsReg().init(0)
    val headCntOvf = headCnt === (head / numOfCore) - 1
    val lastHead = Bool().setAsReg().init(False)
    val headVld = Bool()
    when(headVld) {
      headCnt := headCnt + 1
      when(headCnt === (head / numOfCore) - 2)(lastHead.set())
      when(headCntOvf) {
        lastHead.clear()
        headCnt.clearAll()
      }
    }

    val layerCnt = UInt(log2Up(layer) bits).setAsReg().init(0)
    val lastLayer = Bool().setAsReg().init(False)
    val layerCntAbout2Ovf = layerCnt === layer - 2
    val enLayerCntInc = Bool()
    when(enLayerCntInc) {
      layerCnt := layerCnt + 1
      when(layerCntAbout2Ovf)(lastLayer.set())
      when(lastLayer) {
        layerCnt.clearAll()
        lastLayer.clear()
      }
    }

    val prefill = prefillInLock.payload
    val prefillFirstToken = Bool().setAsReg().init(True)

    prefillInLock.ready := enLayerCntInc & lastLayer
    prefillFirstToken.clearWhen(prefillInLock.fire)

    headVld := io.busIn.valid & io.busIn.last & attnVHit
    enLayerCntInc := io.busIn.valid & io.busIn.last & mlpDHit

    when(prefill & lastLayer) {
      enLayerCntInc := headVld & headCntOvf
    }

    val noAttn = prefillFirstToken || prefill & lastLayer
    val tokenNextHit = attnVHit & prefill & lastLayer & lastHead || lmHeadHit
    val vNextHit = attnVHit & ~noAttn
    val mlpNextHit = mlpDHit
    status.tokenNextHit := tokenNextHit
    status.vNextHit := vNextHit
    status.mlpNextHit := mlpNextHit
    status.prefill := prefill
  }

  val dataAlign = new Area {

    val prefillIn = Stream(Bool())
    prefillIn.valid := io.tokenIndexFlow.valid
    prefillIn.payload := io.tokenIndexFlow.payload === 0
    val prefillInLock = prefillIn.queue(64, forFMax = true)

    val mlpHit = io.engineOut.payload === mlpTensorTag
    val vHit = io.dotOut.payload === vTensorTag._1 || io.dotOut.payload === vTensorTag._2

    val engineOutCnt = UInt(log2Up(engineOutVecCnt) bits).setAsReg().init(0)
    val engineOutCntOvf = engineOutCnt === engineOutVecCnt - 1
    val engineOutVld = Bool()
    when(engineOutVld) {
      engineOutCnt := engineOutCnt + 1
      when(engineOutCntOvf) {
        engineOutCnt.clearAll()
      }
    }

    val dotOutCnt = UInt(log2Up(dotOutVecCnt) bits).setAsReg().init(0)
    val dotOutCntOvf = dotOutCnt === dotOutVecCnt - 1
    val dotOutVld = Bool()
    when(dotOutVld) {
      dotOutCnt := dotOutCnt + 1
      when(dotOutCntOvf) {
        dotOutCnt.clearAll()
      }
    }

    val mlpDone = engineOutVld & engineOutCntOvf
    val vDone = dotOutVld & dotOutCntOvf
    engineOutVld := io.engineOut.valid & mlpHit
    dotOutVld := io.dotOut.valid & vHit

    val prefill = prefillInLock.payload
    val prefillFirstToken = Bool().setAsReg().init(True)
    val lastLayer = Bool().setAsReg().init(False)

    val enInc = Bool()
    val layerCnt = UInt(log2Up(layer) bits).setAsReg().init(0)
    val tokenCnt = UInt(log2Up(maxToken) bits).setAsReg().init(0)
    val layerOvf = layerCnt === layer - 1
    val beforeLastLayer = layerCnt === layer - 2

    when(enInc) {
      layerCnt := layerCnt + 1
      when(beforeLastLayer) {
        lastLayer.set()
      }
      when(layerOvf) {
        layerCnt.clearAll()
        lastLayer.clear()
        prefillFirstToken.clear()
        tokenCnt := tokenCnt + 1
        when(tokenCnt === maxToken - 1) {
          tokenCnt.clearAll()
        }
      }
    }

    enInc := mlpDone
    when(prefill & lastLayer) {
      enInc := vDone
    }

    prefillInLock.ready := enInc & lastLayer

    val nonZeroCnt = Bits(16 bits).setAsReg().init(0xffff)
    when(io.gtCnt.valid)(nonZeroCnt := io.gtCnt.payload)
    when(enInc)(nonZeroCnt := 0xffff)

    val flushRes = Mux(prefill, beforeLastLayer, lastLayer) & engineOutVld
    val nextLayer = enInc

    val logitsGen = Bool().setAsReg().init(False)
    logitsGen.setWhen(~prefill & mlpDone & lastLayer).clearWhen(status.argmaxVld)

    status.flushRes := flushRes
    status.nextLayer := nextLayer
    status.nonZeroCnt := nonZeroCnt.asBits
    status.logitsGen := logitsGen
    status.toLogitsGen := ~prefill & lastLayer
    status.token := tokenCnt
    status.layerCnt := layerCnt
    status.projectionDone := io.projectionDone
    status.projectionError := io.projectionError

    //    tokenCnt.addAttribute("mark_debug", "true")
    //    layerCnt.addAttribute("mark_debug", "true")
  }
}
