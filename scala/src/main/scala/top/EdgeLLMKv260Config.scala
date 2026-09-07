package top

/** Platform contract for the checked-in KV260 block design.
  *
  * Keep these values separate from experimental multi-core configurations:
  * the imported DataPath_xN RTL exposes one logical compute core whose
  * 512-bit weight stream is striped over four 128-bit ZynqMP HP ports.
  */
object EdgeLLMKv260Config {
  val DMA_SPLIT = List(4)
  val baseAddr = List(0)
  val cmdAddrWidth = List(40)
  val splitBaseAddr = List((BigInt(0x800000000L), BigInt(0x36000)))
  val numOfCore = 1
  val sync = true
  val resetLowPolarity = true

  require(DMA_SPLIT.length == numOfCore, "one DMA split entry is required per core")
  require(baseAddr.length == numOfCore, "one base address is required per core")
  require(cmdAddrWidth.length == numOfCore, "one command width is required per core")
  require(splitBaseAddr.length == numOfCore, "one address remap is required per core")
  require(DMA_SPLIT.sum == 4, "the KV260 block design exposes four HP DMA ports")
  require(cmdAddrWidth.forall(_ == 40), "KV260 DDR addressing requires 40-bit commands")
}
