package util

import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axi._
import spinal.lib.bus.amba4.axilite.AxiLite4
import spinal.lib.bus.amba4.axis._

import scala.language.postfixOps

class SplitAxiDatamover(busWidth: Int, split: Int, bufDepth: Int, offsetTable: List[Int], addressWidth: Int = 32) extends Component {

  val memoryMapDataWidth = busWidth / split
  val mm2sStreamDataWidth = busWidth
  val s2mmStreamDataWidth = busWidth

  val io = new Bundle {
    val s2mm = slave(Axi4Stream(
      Axi4StreamConfig(
        dataWidth = s2mmStreamDataWidth / 8,
        useKeep = true,
        useLast = true
      )))
    val mm2s = master(Axi4Stream(
      Axi4StreamConfig(
        dataWidth = mm2sStreamDataWidth / 8,
        useKeep = true,
        useLast = true
      )))
    val s2mmCmd = slave(Stream(Bits(addressWidth + 40 bits)))
    val mm2sCmd = slave(Stream(Bits(addressWidth + 40 bits)))
    // One aggregate status event is emitted only after every physical HP
    // channel has returned the status beat for the same logical command.
    val s2mmStatus = master(Stream(Bits(8 bits)))
    val mm2sStatus = master(Stream(Bits(8 bits)))
    val s2mmError = out Bool()
    val mm2sError = out Bool()
    val m_axi = Vec(master(Axi4(
      Axi4Config(
        addressWidth = addressWidth,
        dataWidth = memoryMapDataWidth,
        idWidth = 4,
        useBurst = true,
        useCache = true,
        useId = true,
        useLen = true,
        useProt = true,
        useSize = true,
        arUserWidth = 4,
        awUserWidth = 4,
        useLast = true,
        useResp = true,
        useLock = false,
        useQos = false,
        useRegion = false
      )
    )), split)
  }
  val ctrl = slave(AxiLite4(32, 32))

  val aresetn = in Bool()

  noIoPrefix()
  io.m_axi.foreach(Axi4SpecRenamer(_))
  Axi4SpecRenamer(io.s2mm)
  Axi4SpecRenamer(io.mm2s)
  AxiStreamSpecRenamer(io.s2mmCmd)
  AxiStreamSpecRenamer(io.mm2sCmd)

  val postFix = if (addressWidth == 32) "" else "b" + addressWidth.toString
  val dma = Array.fill(split)(
    new AXIDataMoverWrapper(
      memoryMapDataWidth,
      memoryMapDataWidth,
      memoryMapDataWidth,
      "AxiDatamover" + memoryMapDataWidth.toString + postFix,
      cmdBytes = 5 + addressWidth / 8
    ))

//  val stsVld = Bool()
//  val stsData = Bits(8 bits)
//  stsVld := dma.head.io.m_axis_s2mm_sts.valid
//  stsData := dma.head.io.m_axis_s2mm_sts.data
//  stsVld.addAttribute("mark_debug")
//  stsData.addAttribute("mark_debug")
//
//  val s2mmVld = Bool()
//  val s2mmLast = Bool()
//  s2mmVld := dma.head.io.s_axis_s2mm.valid
//  s2mmLast := dma.head.io.s_axis_s2mm.last
//  s2mmVld.addAttribute("mark_debug", "true")
//  s2mmLast.addAttribute("mark_debug", "true")

  dma.foreach(_.io.m_axi_mm2s_aresetn := aresetn)
  dma.foreach(_.io.m_axi_s2mm_aresetn := aresetn)
  dma.foreach(_.io.m_axis_mm2s_cmdsts_aresetn := aresetn)
  dma.foreach(_.io.m_axis_s2mm_cmdsts_aresetn := aresetn)

  val mm2sCmdSplit = new SplitAxiDatamoverCmd(split, offsetTable, addressWidth = addressWidth)
  val s2mmCmdSplit = new SplitAxiDatamoverCmd(split, offsetTable, addressWidth = addressWidth)

  io.mm2sCmd >> mm2sCmdSplit.io.inCmd
  io.s2mmCmd >> s2mmCmdSplit.io.inCmd

  val inBuf = Array.fill(split)(new StreamFifo(Bits(busWidth / split bits), bufDepth, forFMax = true))
  val inLastBuf = new StreamFifo(Bool(), bufDepth, forFMax = true)
  val outBuf = Array.fill(split)(new StreamFifo(Fragment(Bits(busWidth / split bits)), bufDepth, forFMax = true))

  val s2mmFork = new StreamFork(NoData(), split, synchronous = true)
  s2mmFork.io.input.arbitrationFrom(io.s2mm)

  val s2mmPayloadSplit = io.s2mm.data.subdivideIn(split slices)

  for (i <- 0 until split) {
    dma(i).io.s_axis_mm2s_cmd.arbitrationFrom(mm2sCmdSplit.io.outCmd(i))
    dma(i).io.s_axis_mm2s_cmd.data := mm2sCmdSplit.io.outCmd(i).payload
    dma(i).io.s_axis_s2mm_cmd.arbitrationFrom(s2mmCmdSplit.io.outCmd(i))
    dma(i).io.s_axis_s2mm_cmd.data := s2mmCmdSplit.io.outCmd(i).payload
    io.m_axi(i) << dma(i).io.m_axi

    inBuf(i).io.push.arbitrationFrom(dma(i).io.m_axis_mm2s)
    inBuf(i).io.push.payload := dma(i).io.m_axis_mm2s.data

//    inBuf(i).io.push.valid.addAttribute("mark_debug", "true")
//    inBuf(i).io.push.ready.addAttribute("mark_debug", "true")
//    inBuf(i).io.pop.valid.addAttribute("mark_debug", "true")

    outBuf(i).io.push.arbitrationFrom(s2mmFork.io.outputs(i))
    outBuf(i).io.push.last := io.s2mm.last
    outBuf(i).io.push.fragment := s2mmPayloadSplit(i)
    dma(i).io.s_axis_s2mm.arbitrationFrom(outBuf(i).io.pop)
    dma(i).io.s_axis_s2mm.data := outBuf(i).io.pop.fragment
    dma(i).io.s_axis_s2mm.last := outBuf(i).io.pop.last
    dma(i).io.s_axis_s2mm.keep.setAll()

    dma(i).io.m_axi.r.id.removeAssignments()
    dma(i).io.m_axi.b.id.removeAssignments()
  }

  val s2mmStatusJoin = new SplitDmaStatusJoin(split)
  val mm2sStatusJoin = new SplitDmaStatusJoin(split)
  for (i <- 0 until split) {
    s2mmStatusJoin.io.physical(i).valid := dma(i).io.m_axis_s2mm_sts.valid
    s2mmStatusJoin.io.physical(i).payload := dma(i).io.m_axis_s2mm_sts.data
    dma(i).io.m_axis_s2mm_sts.ready := s2mmStatusJoin.io.physical(i).ready
    mm2sStatusJoin.io.physical(i).valid := dma(i).io.m_axis_mm2s_sts.valid
    mm2sStatusJoin.io.physical(i).payload := dma(i).io.m_axis_mm2s_sts.data
    dma(i).io.m_axis_mm2s_sts.ready := mm2sStatusJoin.io.physical(i).ready
  }
  io.s2mmStatus << s2mmStatusJoin.io.logical
  io.mm2sStatus << mm2sStatusJoin.io.logical
  io.s2mmError := dma.map(_.io.s2mm_err).reduce(_ || _)
  io.mm2sError := dma.map(_.io.mm2s_err).reduce(_ || _)

  inLastBuf.io.push.valid := inBuf.head.io.push.fire
  inLastBuf.io.pop.ready := inBuf.head.io.pop.fire
  inLastBuf.io.push.payload := dma.head.io.m_axis_mm2s.last

  val inBufPayload = Vec(inBuf.map(_.io.pop.payload)).asBits
  val joinEvent = StreamJoin(inBuf.map(_.io.pop.toEvent()))

  val mm2s = Axi4Stream(
    Axi4StreamConfig(
      dataWidth = mm2sStreamDataWidth / 8,
      useKeep = true,
      useLast = true
    ))

  mm2s.arbitrationFrom(joinEvent)
  mm2s.data := inBufPayload
  mm2s.keep.setAll()
  mm2s.last := inLastBuf.io.pop.payload
  io.mm2s << mm2s.m2sPipe()
}
//
//object SplitAxiDatamover extends App {
//  val clock = new ClockDomainConfig(resetActiveLevel = LOW)
//  SpinalConfig(defaultConfigForClockDomains = clock).generateVerilog(new SplitAxiDatamover(128, 4, 512))
//}
