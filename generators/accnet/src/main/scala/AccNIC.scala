package accnet

import chisel3._
import chisel3.util._
import chisel3.reflect.DataMirror
import freechips.rocketchip.subsystem.{BaseSubsystem, TLBusWrapperLocation, PBUS, FBUS}
import org.chipsalliance.cde.config.{Field, Parameters}
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.prci._
import freechips.rocketchip.regmapper._
import freechips.rocketchip.interrupts._
import freechips.rocketchip.tilelink._
import freechips.rocketchip.util._
import AccNetConsts._
import chisel3.experimental.{IntParam}


// Copied from testchipip to avoid dependency
class ClockedIO[T <: Data](private val gen: T) extends Bundle {
  val clock = Output(Clock())
  val bits = DataMirror.internal.chiselTypeClone[T](gen)
}

class AccNICIO extends StreamIO(NET_IF_WIDTH) {
  val macAddr = Input(UInt(ETH_MAC_BITS.W))
  val rlimit = Input(new RateLimiterSettings)
}

class AccNICIOvonly extends Bundle {
  val in = Flipped(Valid(new StreamChannel(NET_IF_WIDTH)))
  val out = Valid(new StreamChannel(NET_IF_WIDTH))
  val macAddr = Input(UInt(ETH_MAC_BITS.W))
  val rlimit = Input(new RateLimiterSettings)
}

object AccNICIO {
  def apply(vonly: AccNICIOvonly): AccNICIO = {
    val nicio = Wire(new AccNICIO)
    assert(!vonly.out.valid || nicio.out.ready)
    nicio.out.valid := vonly.out.valid
    nicio.out.bits  := vonly.out.bits
    vonly.in.valid  := nicio.in.valid
    vonly.in.bits   := nicio.in.bits
    nicio.in.ready  := true.B
    vonly.macAddr   := nicio.macAddr
    vonly.rlimit    := nicio.rlimit
    nicio
  }
}

object AccNICIOvonly {
  def apply(nicio: AccNICIO): AccNICIOvonly = {
    val vonly = Wire(new AccNICIOvonly)
    vonly.out.valid := nicio.out.valid
    vonly.out.bits  := nicio.out.bits
    nicio.out.ready := true.B
    nicio.in.valid  := vonly.in.valid
    nicio.in.bits   := vonly.in.bits
    assert(!vonly.in.valid || nicio.in.ready, "NIC input not ready for valid")
    nicio.macAddr := vonly.macAddr
    nicio.rlimit  := vonly.rlimit
    vonly
  }
}

class PacketArbiter(arbN: Int, rr: Boolean = false)
  extends HellaPeekingArbiter(
    new StreamChannel(NET_IF_WIDTH), arbN,
    (ch: StreamChannel) => ch.last, rr = rr)
    
/**
 * @inBufFlits How many flits in the input buffer(s)
 * @outBufFlits Number of flits in the output buffer
 * @nMemXacts Maximum number of transactions that the send/receive path can send to memory
 * @maxAcquireBytes Cache block size
 * @ctrlQueueDepth Depth of the MMIO control queues
 * @checksumOffload TCP checksum offload engine
 * @packetMaxBytes Maximum number of bytes in a packet (header size + MTU)
 */
case class  AccNICConfig(
  inBufFlits: Int  = 2 * ETH_STANDARD_MAX_BYTES / NET_IF_BYTES,
  outBufFlits: Int = 2 * ETH_STANDARD_MAX_BYTES / NET_IF_BYTES,
  nMemXacts: Int = 8,
  maxAcquireBytes: Int = 64,
  ctrlQueueDepth: Int = 10,
  checksumOffload: Boolean = false,
  packetMaxBytes: Int = ETH_STANDARD_MAX_BYTES)

case class AccNICAttachParams(
  masterWhere: TLBusWrapperLocation = FBUS,
  slaveWhere: TLBusWrapperLocation = PBUS
)

case object AccNICKey extends Field[Option[ AccNICConfig]](None)
case object AccNICAttachKey extends Field[AccNICAttachParams](AccNICAttachParams())

case class AccNicControllerParams(address: BigInt, beatBytes: Int)

trait HasNICParameters {
  implicit val p: Parameters
  val nicExternal = p(AccNICKey).get
  val inBufFlits      = nicExternal.inBufFlits
  val outBufFlits     = nicExternal.outBufFlits
  val nMemXacts       = nicExternal.nMemXacts
  val maxAcquireBytes = nicExternal.maxAcquireBytes
  val ctrlQueueDepth  = nicExternal.ctrlQueueDepth
  val checksumOffload = nicExternal.checksumOffload
  val packetMaxBytes  = nicExternal.packetMaxBytes
}

abstract class NICLazyModule(implicit p: Parameters)
  extends LazyModule with HasNICParameters

abstract class NICModule(implicit val p: Parameters)
  extends Module with HasNICParameters

abstract class NICBundle(implicit val p: Parameters)
  extends Bundle with HasNICParameters

// MMIO controller (RegisterRouter)
class AccNicController(c: AccNicControllerParams)(implicit p: Parameters)
    extends RegisterRouter(RegisterRouterParams(
      name = "acc-nic",
      compat = Seq("yale-systems,acc-nic"),
      base = c.address,
      beatBytes = c.beatBytes
    ))
    with HasTLControlRegMap
    with HasInterruptSources
    with HasNICParameters {

  override def nInterrupts = 2 // [0] = TX, [1] = RX

  override lazy val module = new AccNicControllerModuleImp(this)

  def tlRegmap(mapping: RegField.Map*): Unit = regmap(mapping: _*)
}

class AccNicControllerModuleImp(outer: AccNicController)(implicit p: Parameters)
    extends LazyModuleImp(outer) with HasNICParameters {

  // val io = IO(new Bundle with AccNicControllerBundle)
  class AccNicControllerIO extends Bundle {
    val txInterrupt = Input(Bool())
    val rxInterrupt = Input(Bool())
  }

  val io = IO(new AccNicControllerIO)

  val intMask   = RegInit(0.U(2.W))
  val intStatus = Wire(Vec(2, Bool()))

  intStatus(0) := io.txInterrupt
  intStatus(1) := io.rxInterrupt

  outer.interrupts(0) := intStatus(0) && intMask(0)
  outer.interrupts(1) := intStatus(1) && intMask(1)

  // when (intMask(0) > 0.U) {
  //   printf("[AccNicController] TX interrupt mask set.\n")
  // }
  // when (intMask(1) > 0.U) {
  //   printf("[AccNicController] RX interrupt mask set.\n")
  // }

  outer.tlRegmap(
    0x00 -> Seq(RegField(2, intMask))
  )
}

// Top-level NIC module
class AccNIC(address: BigInt, beatBytes: Int = 8)
            (implicit p: Parameters) extends NICLazyModule {

  println(s"[AccNIC] Instantiating AccNIC with address = $address, beatBytes = $beatBytes")

  val control  = LazyModule(new AccNicController(AccNicControllerParams(address, beatBytes)))
  val rxEngine = LazyModule(new RxEngine(AccNicControllerParams(address+0x1000, beatBytes)))
  val txEngine = LazyModule(new TxEngine(AccNicControllerParams(address+0x2000, beatBytes)))

  val mmionode_control = TLIdentityNode()
  val mmionode_tx      = TLIdentityNode()
  val mmionode_rx      = TLIdentityNode()
  val dmanode_tx       = TLIdentityNode()
  val dmanode_rx       = TLIdentityNode()
  val intnode          = control.intXing(NoCrossing)

  control.node  := TLAtomicAutomata() := mmionode_control
  txEngine.node := TLAtomicAutomata() := mmionode_tx
  rxEngine.node := TLAtomicAutomata() := mmionode_rx

  dmanode_tx := TLWidthWidget(NET_IF_BYTES) := txEngine.reader.node
  dmanode_rx := TLWidthWidget(NET_IF_BYTES) := rxEngine.writer.node

  lazy val module = new Impl

  class Impl extends LazyModuleImp(this) {
    val io = IO(new Bundle {
      val ext = new AccNICIO
    })

    // External connections
    rxEngine.module.io.ext         <> io.ext.in
    io.ext.out                     <> txEngine.module.io.ext

    control.module.io.rxInterrupt := rxEngine.module.io.interrupt
    control.module.io.txInterrupt := txEngine.module.io.interrupt
  }
}

// Subsystem attachment trait
trait CanHavePeripheryAccNIC { this: BaseSubsystem =>
  private val address = BigInt(0x10018000)
  private val portName = "Acc-NIC"
  private val txName = "accnic-TxEngine"
  private val rxName = "accnic-RxEngine"

  val accnicOpt = p(AccNICKey).map { params =>
    val manager = locateTLBusWrapper(p(AccNICAttachKey).slaveWhere)
    val client  = locateTLBusWrapper(p(AccNICAttachKey).masterWhere)
    val domain  = manager.generateSynchronousDomain.suggestName("accnic_domain")

    val accnic = domain { LazyModule(new AccNIC(address, manager.beatBytes)) }

    // MMIO connections
    manager.coupleTo(portName) { accnic.mmionode_control := TLFragmenter(manager.beatBytes, manager.blockBytes) := _ }
    manager.coupleTo(txName)   { accnic.mmionode_tx      := TLFragmenter(manager.beatBytes, manager.blockBytes) := _ }
    manager.coupleTo(rxName)   { accnic.mmionode_rx      := TLFragmenter(manager.beatBytes, manager.blockBytes) := _ }

    // DMA memory ports
    client.coupleFrom(txName) { _ :=* accnic.dmanode_tx }
    client.coupleFrom(rxName) { _ :=* accnic.dmanode_rx }

    // Interrupts
    ibus.fromSync := accnic.intnode

    // External NIC IO
    val inner_io = domain { InModuleBody {
      val inner_io = IO(new AccNICIOvonly).suggestName("nic")
      inner_io <> AccNICIOvonly(accnic.module.io.ext)
      inner_io
    } }

    val outer_io = InModuleBody {
      val outer_io = IO(new ClockedIO(new AccNICIOvonly)).suggestName("nic")
      outer_io.bits  <> inner_io
      outer_io.clock := domain.module.clock
      outer_io
    }

    outer_io
  }
}

object AccNicLoopback {

  def connect(net: Option[AccNICIOvonly], nicConf: Option[ AccNICConfig], qDepth: Int, latency: Int = 10): Unit = {
    net.foreach { netio =>
      import PauseConsts.BT_PER_QUANTA

      val packetWords = nicConf.get.packetMaxBytes / NET_IF_BYTES
      val packetQuanta = (nicConf.get.packetMaxBytes * 8) / BT_PER_QUANTA

      // Configure MAC address and rate limiter
      netio.macAddr := PlusArg("macaddr", BigInt("112233445566", 16), width = ETH_MAC_BITS)
      netio.rlimit.inc := PlusArg("rlimit-inc", 1)
      netio.rlimit.period := PlusArg("rlimit-period", 1)
      netio.rlimit.size := PlusArg("rlimit-size", 8)

      // Connect in and out with a pipeline (loopback)
      netio.in <> Pipe(netio.out, latency)
      // netio.in.bits.keep := NET_FULL_KEEP
    }
  }

  def connect(net: AccNICIOvonly, nicConf:  AccNICConfig): Unit = {
    val packetWords = nicConf.packetMaxBytes / NET_IF_BYTES
    AccNicLoopback.connect(Some(net), Some(nicConf), 4 * packetWords)
  }
}

class FlippedQSFPIO() extends Bundle {
  val tx_p      = Output(UInt(4.W))
  val tx_n      = Output(UInt(4.W))
  val rx_p      = Input(UInt(4.W))
  val rx_n      = Input(UInt(4.W))
  val refclk_p  = Input(Bool())
  val refclk_n  = Input(Bool())
  val modsell   = Output(Bool())
  val resetl    = Output(Bool())
  val modprsl   = Input(Bool())
  val intl      = Input(Bool())
  val lpmode    = Output(Bool())
}

class QSFPCore extends BlackBox(Map("DATA_WIDTH" -> IntParam(NET_IF_WIDTH))) 
with HasBlackBoxResource {
  val io = IO(new Bundle {
    val clk = Input(Clock())
    val rst = Input(Bool())
    val qsfp1 = new FlippedQSFPIO
    val tx = Flipped(new StreamChannelFull(NET_IF_WIDTH))
    val rx = new StreamChannelFull(NET_IF_WIDTH)
    val clk_125mhz_int = Input(Clock())
    val rst_125mhz_int = Input(Bool())
  })
  addResource("/vsrc/mac_ts_insert.v")
  addResource("/vsrc/axis_async_fifo.v")
  addResource("/vsrc/axis_adapter.v")
  addResource("/vsrc/cmac_pad.v")
  addResource("/vsrc/cmac_gty_ch_wrapper.v")
  addResource("/vsrc/cmac_gty_wrapper.v")
  addResource("/vsrc/sync_reset.v")
  addResource("/vsrc/sync_signal.v")
  addResource("/vsrc/QSFPCore.v")
}

object AccNicQSFP {
  def connect(qsfpExternal: FlippedQSFPIO, net: Option[AccNICIOvonly], nicConf: AccNICConfig, clock: Clock, reset: Bool): Unit = {
    val qsfpCore = Module(new QSFPCore)

    net.foreach { netio =>
      netio.macAddr := PlusArg("macaddr", BigInt("112233445566", 16), width = ETH_MAC_BITS)
      netio.rlimit.inc := PlusArg("rlimit-inc", 1)
      netio.rlimit.period := PlusArg("rlimit-period", 1)
      netio.rlimit.size := PlusArg("rlimit-size", 8)

      qsfpCore.io.clk := clock
      qsfpCore.io.rst := reset

      qsfpCore.io.clk_125mhz_int := clock
      qsfpCore.io.rst_125mhz_int := reset

      qsfpExternal.tx_p       := qsfpCore.io.qsfp1.tx_p
      qsfpExternal.tx_n       := qsfpCore.io.qsfp1.tx_n
      qsfpCore.io.qsfp1.rx_p  := qsfpExternal.rx_p
      qsfpCore.io.qsfp1.rx_n  := qsfpExternal.rx_n

      qsfpCore.io.qsfp1.refclk_p := qsfpExternal.refclk_p
      qsfpCore.io.qsfp1.refclk_n := qsfpExternal.refclk_n

      qsfpExternal.modsell      := qsfpCore.io.qsfp1.modsell
      qsfpExternal.resetl       := qsfpCore.io.qsfp1.resetl
      qsfpCore.io.qsfp1.modprsl := qsfpExternal.modprsl
      qsfpCore.io.qsfp1.intl    := qsfpExternal.intl
      qsfpExternal.lpmode       := qsfpCore.io.qsfp1.lpmode

      qsfpCore.io.tx.data     := netio.out.bits.data
      qsfpCore.io.tx.keep     := netio.out.bits.keep
      qsfpCore.io.tx.last     := netio.out.bits.last
      qsfpCore.io.tx.user     := false.B // No user data in this example
      qsfpCore.io.tx.valid    := netio.out.valid

      netio.in.bits.data      := qsfpCore.io.rx.data
      netio.in.bits.keep      := qsfpCore.io.rx.keep
      netio.in.bits.last      := qsfpCore.io.rx.last
      netio.in.valid          := qsfpCore.io.rx.valid
      qsfpCore.io.rx.ready  := true.B
    }
  }
}