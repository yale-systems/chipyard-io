// // package accnet

// import chisel3._
// import chisel3.util._
// import chisel3.reflect.DataMirror
// import freechips.rocketchip.subsystem.{BaseSubsystem, TLBusWrapperLocation, PBUS, FBUS}
// import org.chipsalliance.cde.config.{Field, Parameters}
// import freechips.rocketchip.diplomacy._
// import freechips.rocketchip.prci._
// import freechips.rocketchip.regmapper._
// import freechips.rocketchip.interrupts._
// import freechips.rocketchip.tilelink._
// import freechips.rocketchip.util._
// import AccNetConsts._

// // This is copied from testchipip to avoid dependencies
// class ClockedIO[T <: Data](private val gen: T) extends Bundle {
//   val clock = Output(Clock())
//   val bits = DataMirror.internal.chiselTypeClone[T](gen)
// }

// /**
//  * @inBufFlits How many flits in the input buffer(s)
//  * @outBufFlits Number of flits in the output buffer
//  * @nMemXacts Maximum number of transactions that the send/receive path can send to memory
//  * @maxAcquireBytes Cache block size
//  * @ctrlQueueDepth Depth of the MMIO control queues
//  * @checksumOffload TCP checksum offload engine
//  * @packetMaxBytes Maximum number of bytes in a packet (header size + MTU)
//  */
// case class NICConfig(
//   inBufFlits: Int  = 2 * ETH_STANDARD_MAX_BYTES / NET_IF_BYTES,
//   outBufFlits: Int = 2 * ETH_STANDARD_MAX_BYTES / NET_IF_BYTES,
//   nMemXacts: Int = 8,
//   maxAcquireBytes: Int = 64,
//   ctrlQueueDepth: Int = 10,
//   checksumOffload: Boolean = false,
//   packetMaxBytes: Int = ETH_STANDARD_MAX_BYTES)

// case class NICAttachParams(
//   masterWhere: TLBusWrapperLocation = FBUS,
//   slaveWhere: TLBusWrapperLocation = PBUS
// )

// case object AccNICKey extends Field[Option[NICConfig]](None)
// case object NICAttachKey extends Field[NICAttachParams](NICAttachParams())

// trait HasNICParameters {
//   implicit val p: Parameters
//   val nicExternal = p(AccNICKey).get
//   val inBufFlits = nicExternal.inBufFlits
//   val outBufFlits = nicExternal.outBufFlits
//   val nMemXacts = nicExternal.nMemXacts
//   val maxAcquireBytes = nicExternal.maxAcquireBytes
//   val ctrlQueueDepth = nicExternal.ctrlQueueDepth
//   val checksumOffload = nicExternal.checksumOffload
//   val packetMaxBytes = nicExternal.packetMaxBytes
// }

// abstract class NICLazyModule(implicit p: Parameters)
//   extends LazyModule with HasNICParameters

// abstract class NICModule(implicit val p: Parameters)
//   extends Module with HasNICParameters

// abstract class NICBundle(implicit val p: Parameters)
//   extends Bundle with HasNICParameters

// class PacketArbiter(arbN: Int, rr: Boolean = false)
//   extends HellaPeekingArbiter(
//     new StreamChannel(NET_IF_WIDTH), arbN,
//     (ch: StreamChannel) => ch.last, rr = rr)

// class NICIO extends StreamIO(NET_IF_WIDTH) {
//   val macAddr = Input(UInt(ETH_MAC_BITS.W))
//   val rlimit = Input(new RateLimiterSettings)
// }

// class NICIOvonly extends Bundle {
//   val in = Flipped(Valid(new StreamChannel(NET_IF_WIDTH)))
//   val out = Valid(new StreamChannel(NET_IF_WIDTH))
//   val macAddr = Input(UInt(ETH_MAC_BITS.W))
//   val rlimit = Input(new RateLimiterSettings)
// }

// object NICIO {
//   def apply(vonly: NICIOvonly): NICIO = {
//     val nicio = Wire(new NICIO)
//     assert(!vonly.out.valid || nicio.out.ready)
//     nicio.out.valid := vonly.out.valid
//     nicio.out.bits  := vonly.out.bits
//     vonly.in.valid  := nicio.in.valid
//     vonly.in.bits   := nicio.in.bits
//     nicio.in.ready  := true.B
//     vonly.macAddr   := nicio.macAddr
//     vonly.rlimit    := nicio.rlimit
//     nicio
//   }
// }

// object NICIOvonly {
//   def apply(nicio: NICIO): NICIOvonly = {
//     val vonly = Wire(new NICIOvonly)
//     vonly.out.valid := nicio.out.valid
//     vonly.out.bits  := nicio.out.bits
//     nicio.out.ready := true.B
//     nicio.in.valid  := vonly.in.valid
//     nicio.in.bits   := vonly.in.bits
//     assert(!vonly.in.valid || nicio.in.ready, "NIC input not ready for valid")
//     nicio.macAddr := vonly.macAddr
//     nicio.rlimit  := vonly.rlimit
//     vonly
//   }
// }






// /*
//  * Take commands from the CPU over TL2, expose as Queues
//  */
// class AccNicController(c: AccNicControllerParams)(implicit p: Parameters)
//     extends RegisterRouter(RegisterRouterParams("acc-nic", Seq("yale-systems,acc-nic"),
//       c.address, beatBytes=c.beatBytes))
//     with HasTLControlRegMap
//     with HasInterruptSources
//     with HasNICParameters {
//   override def nInterrupts = 2
//   def tlRegmap(mapping: RegField.Map*): Unit = regmap(mapping:_*)
//   override lazy val module = new AccNicControllerModuleImp(this)
// }

// class AccNicControllerModuleImp(outer: AccNicController)(implicit p: Parameters) 
//       extends LazyModuleImp(outer) with HasNICParameters {
//   val io = IO(new Bundle with AccNicControllerBundle)

//   val sendCompDown = WireInit(false.B)

//   val qDepth = ctrlQueueDepth
//   require(qDepth < (1 << 8))

//   def queueCount[T <: Data](qio: QueueIO[T], depth: Int): UInt =
//     TwoWayCounter(qio.enq.fire, qio.deq.fire, depth)

//   // hold (len, addr) of packets that we need to send out
//   val sendReqQueue = Module(new HellaQueue(qDepth)(UInt(NET_IF_WIDTH.W)))
//   val sendReqCount = queueCount(sendReqQueue.io, qDepth)
//   // hold addr of buffers we can write received packets into
//   val recvReqQueue = Module(new HellaQueue(qDepth)(UInt(NET_IF_WIDTH.W)))
//   val recvReqCount = queueCount(recvReqQueue.io, qDepth)
//   // count number of sends completed
//   val sendCompCount = TwoWayCounter(io.send.comp.fire, sendCompDown, qDepth)
//   // hold length of received packets
//   val recvCompQueue = Module(new HellaQueue(qDepth)(UInt(NET_LEN_BITS.W)))
//   val recvCompCount = queueCount(recvCompQueue.io, qDepth)

//   val udpDstPort = RegInit(0.U(16.W))
//   val udpMemBase = RegInit(0.U(64.W))
//   val udpMemOffset = RegInit(0.U(32.W)) // optional

//   val sendCompValid = sendCompCount > 0.U
//   val intMask = RegInit(0.U(2.W))

//   io.send.req <> sendReqQueue.io.deq
//   io.recv.req <> recvReqQueue.io.deq
//   io.send.comp.ready := sendCompCount < qDepth.U
//   recvCompQueue.io.enq <> io.recv.comp

//   outer.interrupts(0) := sendCompValid && intMask(0)
//   outer.interrupts(1) := recvCompQueue.io.deq.valid && intMask(1)

//   val sendReqSpace = (qDepth.U - sendReqCount)
//   val recvReqSpace = (qDepth.U - recvReqCount)

//   def sendCompRead = (ready: Bool) => {
//     sendCompDown := sendCompValid && ready
//     (sendCompValid, true.B)
//   }

//   val txcsumReqQueue = Module(new HellaQueue(qDepth)(UInt(49.W)))
//   val rxcsumResQueue = Module(new HellaQueue(qDepth)(UInt(2.W)))
//   val csumEnable = RegInit(false.B)

//   io.txcsumReq.valid := txcsumReqQueue.io.deq.valid
//   io.txcsumReq.bits := txcsumReqQueue.io.deq.bits.asTypeOf(new ChecksumRewriteRequest)
//   txcsumReqQueue.io.deq.ready := io.txcsumReq.ready

//   rxcsumResQueue.io.enq.valid := io.rxcsumRes.valid
//   rxcsumResQueue.io.enq.bits := io.rxcsumRes.bits.asUInt
//   io.rxcsumRes.ready := rxcsumResQueue.io.enq.ready

//   io.csumEnable := csumEnable

//   outer.tlRegmap(
//     0x00 -> Seq(RegField.w(NET_IF_WIDTH, sendReqQueue.io.enq)),
//     0x08 -> Seq(RegField.w(NET_IF_WIDTH, recvReqQueue.io.enq)),
//     0x10 -> Seq(RegField.r(1, sendCompRead)),
//     0x12 -> Seq(RegField.r(NET_LEN_BITS, recvCompQueue.io.deq)),
//     0x14 -> Seq(
//       RegField.r(8, sendReqSpace),
//       RegField.r(8, recvReqSpace),
//       RegField.r(8, sendCompCount),
//       RegField.r(8, recvCompCount)),
//     0x18 -> Seq(RegField.r(ETH_MAC_BITS, io.macAddr)),
//     0x20 -> Seq(RegField(2, intMask)),
//     0x28 -> Seq(RegField.w(49, txcsumReqQueue.io.enq)),
//     0x30 -> Seq(RegField.r(2, rxcsumResQueue.io.deq)),
//     0x31 -> Seq(RegField(1, csumEnable)),
//     0x40 -> Seq(RegField(16, udpDstPort)),
//     0x42 -> Seq(RegField(64, udpMemBase)),
//     0x4A -> Seq(RegField(32, udpMemOffset))
//   )
// }

// class AccNicSendPath()(implicit p: Parameters) extends NICLazyModule {
//   val reader = LazyModule(new StreamReader(nMemXacts, outBufFlits, maxAcquireBytes))
//   val node = reader.node

//   lazy val module = new Impl
//   class Impl extends LazyModuleImp(this) {
//     val io = IO(new Bundle {
//       val send = Flipped(new AccNicSendIO)
//       val out = Decoupled(new StreamChannel(NET_IF_WIDTH))
//       val rlimit = Input(new RateLimiterSettings)
//       val csum = checksumOffload.option(new Bundle {
//         val req = Flipped(Decoupled(new ChecksumRewriteRequest))
//         val enable = Input(Bool())
//       })
//     })

//     val readreq = reader.module.io.req
//     io.send.req.ready := readreq.ready
//     readreq.valid := io.send.req.valid
//     readreq.bits.address := io.send.req.bits(47, 0)
//     readreq.bits.length  := io.send.req.bits(62, 48)
//     readreq.bits.partial := io.send.req.bits(63)
//     io.send.comp <> reader.module.io.resp

//     val preArbOut = if (checksumOffload) {
//       val readerOut = reader.module.io.out
//       val arb = Module(new PacketArbiter(2))
//       val bufFlits = (packetMaxBytes - 1) / NET_IF_BYTES + 1
//       val rewriter = Module(new ChecksumRewrite(NET_IF_WIDTH, bufFlits))
//       val enable = io.csum.get.enable

//       rewriter.io.req <> io.csum.get.req

//       arb.io.in(0) <> rewriter.io.stream.out
//       arb.io.in(1).valid := !enable && readerOut.valid
//       arb.io.in(1).bits  := readerOut.bits
//       rewriter.io.stream.in.valid := enable && readerOut.valid
//       rewriter.io.stream.in.bits := readerOut.bits
//       readerOut.ready := Mux(enable,
//         rewriter.io.stream.in.ready, arb.io.in(1).ready)

//       arb.io.out
//     } else { reader.module.io.out }

//     val unlimitedOut = preArbOut

//     val limiter = Module(new RateLimiter(new StreamChannel(NET_IF_WIDTH)))
//     limiter.io.in <> unlimitedOut
//     limiter.io.settings := io.rlimit
//     io.out <> limiter.io.out
//   }
// }



// /*
//  * Recv frames
//  */
// class AccNicRecvPath()
//     (implicit p: Parameters) extends LazyModule {
//   val writer = LazyModule(new AccNicWriter)
//   val node = TLIdentityNode()
//   node := writer.node
//   lazy val module = new AccNicRecvPathModule(this)
// }

// class AccNicRecvPathModule(val outer: AccNicRecvPath) extends LazyModuleImp(outer) with HasNICParameters {
//   val io = IO(new Bundle {
//     val recv = Flipped(new AccNicRecvIO)
//     val in = Flipped(Decoupled(new StreamChannel(NET_IF_WIDTH))) // input stream
//     val csum = checksumOffload.option(new Bundle {
//       val res = Decoupled(new TCPChecksumOffloadResult)
//       val enable = Input(Bool())
//     })
//     val buf_free = Output(Vec(1, UInt(8.W)))
//   })

//   val buffer = Module(new NetworkPacketBuffer(inBufFlits, maxBytes = packetMaxBytes))

//   buffer.io.stream.in <> io.in

//   io.buf_free := VecInit(Seq(buffer.io.free))

//   val bufout = buffer.io.stream.out
//   val buflen = buffer.io.length

//   val (csumout, recvreq) = (if (checksumOffload) {
//     val offload = Module(new TCPChecksumOffload(NET_IF_WIDTH))
//     val offloadReady = offload.io.in.ready || !io.csum.get.enable

//     val out = Wire(Decoupled(new StreamChannel(NET_IF_WIDTH)))
//     val recvreq = Wire(Decoupled(UInt(NET_IF_WIDTH.W)))
//     val reqq = Module(new Queue(UInt(NET_IF_WIDTH.W), 1))

//     val enqHelper = DecoupledHelper(
//       io.recv.req.valid, reqq.io.enq.ready, recvreq.ready)
//     val deqHelper = DecoupledHelper(
//       bufout.valid, offloadReady, out.ready, reqq.io.deq.valid)

//     reqq.io.enq.valid := enqHelper.fire(reqq.io.enq.ready)
//     reqq.io.enq.bits := io.recv.req.bits
//     io.recv.req.ready := enqHelper.fire(io.recv.req.valid)
//     recvreq.valid := enqHelper.fire(recvreq.ready)
//     recvreq.bits := io.recv.req.bits

//     out.valid := deqHelper.fire(out.ready)
//     out.bits  := bufout.bits
//     offload.io.in.valid := deqHelper.fire(offloadReady, io.csum.get.enable)
//     offload.io.in.bits := bufout.bits
//     bufout.ready := deqHelper.fire(bufout.valid)
//     reqq.io.deq.ready := deqHelper.fire(reqq.io.deq.valid, bufout.bits.last)

//     io.csum.get.res <> offload.io.result

//     (out, recvreq)
//   } else { (bufout, io.recv.req) })

//   val writer = outer.writer.module
//   writer.io.recv.req <> Queue(recvreq, 1)
//   io.recv.comp <> writer.io.recv.comp
//   writer.io.in <> csumout
//   writer.io.length.valid := buflen.valid
//   writer.io.length.bits  := buflen.bits
// }

// /*
//  * A simple NIC
//  *
//  * Expects ethernet frames (see below), but uses a custom transport
//  * (see ExtBundle)
//  *
//  * Ethernet Frame format:
//  *   2 bytes |  6 bytes  |  6 bytes    | 2 bytes  | 46-1500B
//  *   Padding | Dest Addr | Source Addr | Type/Len | Data
//  *
//  * @address Starting address of MMIO control registers
//  * @beatBytes Width of memory interface (in bytes)
//  *
//  */
// class AccNIC(address: BigInt, beatBytes: Int = 8)
//     (implicit p: Parameters) extends NICLazyModule {

//   val control = LazyModule(new AccNicController(
//     AccNicControllerParams(address, beatBytes)))
//   val sendPath = LazyModule(new AccNicSendPath())
//   val recvPath = LazyModule(new AccNicRecvPath())

//   val mmionode = TLIdentityNode()
//   val dmanode = TLIdentityNode()
//   val intnode = control.intXing(NoCrossing)

//   control.node := TLAtomicAutomata() := mmionode
//   dmanode := TLWidthWidget(NET_IF_BYTES) := sendPath.node
//   dmanode := TLWidthWidget(NET_IF_BYTES) := recvPath.node

//   lazy val module = new Impl
//   class Impl extends LazyModuleImp(this) {
//     val io = IO(new Bundle {
//       val ext = new NICIO
//     })

//     sendPath.module.io.send <> control.module.io.send
//     recvPath.module.io.recv <> control.module.io.recv

//     // connect externally
//     recvPath.module.io.in <> io.ext.in
//     io.ext.out <> sendPath.module.io.out


//     control.module.io.macAddr := io.ext.macAddr
//     sendPath.module.io.rlimit := io.ext.rlimit

//     if (checksumOffload) {
//       sendPath.module.io.csum.get.req <> control.module.io.txcsumReq
//       sendPath.module.io.csum.get.enable := control.module.io.csumEnable
//       control.module.io.rxcsumRes <> recvPath.module.io.csum.get.res
//       recvPath.module.io.csum.get.enable := control.module.io.csumEnable
//     } else {
//       control.module.io.txcsumReq.ready := false.B
//       control.module.io.rxcsumRes.valid := false.B
//       control.module.io.rxcsumRes.bits := DontCare
//     }
//   }
// }

// class SimNetwork extends BlackBox with HasBlackBoxResource {
//   val io = IO(new Bundle {
//     val clock = Input(Clock())
//     val reset = Input(Bool())
//     val net = Flipped(new NICIOvonly)
//   })
//   addResource("/vsrc/AccNICSimNetwork.v")
//   addResource("/csrc/AccNICSimNetwork.cc")
//   addResource("/csrc/AccNICdevice.h")
//   addResource("/csrc/AccNICdevice.cc")
//   addResource("/csrc/AccNICswitch.h")
//   addResource("/csrc/AccNICswitch.cc")
//   addResource("/csrc/AccNICpacket.h")
// }

// trait CanHavePeripheryAccNIC  { this: BaseSubsystem =>
//   private val address = BigInt(0x10018000)
//   private val portName = "Acc-NIC"


//   val accnicOpt = p(AccNICKey).map { params =>
//     val manager = locateTLBusWrapper(p(NICAttachKey).slaveWhere)
//     val client = locateTLBusWrapper(p(NICAttachKey).masterWhere)
//     // TODO: currently the controller is in the clock domain of the bus which masters it
//     // we assume this is same as the clock domain of the bus the controller masters
//     val domain = manager.generateSynchronousDomain.suggestName("accnic_domain")

//     val accnic = domain { LazyModule(new AccNIC(address, manager.beatBytes)) }
//     // val accnic = domain { LazyModule(new DummyNIC(address, manager.beatBytes)) }


//     manager.coupleTo(portName) { accnic.mmionode := TLFragmenter(manager.beatBytes, manager.blockBytes) := _ }
//     client.coupleFrom(portName) { _ :=* accnic.dmanode }
//     ibus.fromSync := accnic.intnode

//     val inner_io = domain { InModuleBody {
//       val inner_io = IO(new NICIOvonly).suggestName("nic")
//       inner_io <> NICIOvonly(accnic.module.io.ext)
//       inner_io
//     } }

//     val outer_io = InModuleBody {
//       val outer_io = IO(new ClockedIO(new NICIOvonly)).suggestName("nic")
//       outer_io.bits <> inner_io
//       outer_io.clock := domain.module.clock
//       outer_io
//     }
//     outer_io
//   }
// }

// object NicLoopback {
//   def connect(net: Option[NICIOvonly], nicConf: Option[NICConfig], qDepth: Int, latency: Int = 10): Unit = {
//     net.foreach { netio =>
//       import PauseConsts.BT_PER_QUANTA
//       val packetWords = nicConf.get.packetMaxBytes / NET_IF_BYTES
//       val packetQuanta = (nicConf.get.packetMaxBytes * 8) / BT_PER_QUANTA
//       netio.macAddr := PlusArg("macaddr", BigInt("112233445566", 16), width = ETH_MAC_BITS)
//       netio.rlimit.inc := PlusArg("rlimit-inc", 1)
//       netio.rlimit.period := PlusArg("rlimit-period", 1)
//       netio.rlimit.size := PlusArg("rlimit-size", 8)

//       netio.in := Pipe(netio.out, latency)
//       netio.in.bits.keep := NET_FULL_KEEP
//     }
//   }

//   def connect(net: NICIOvonly, nicConf: NICConfig): Unit = {
//     val packetWords = nicConf.packetMaxBytes / NET_IF_BYTES
//     NicLoopback.connect(Some(net), Some(nicConf), 4 * packetWords)
//   }
// }

// object SimNetwork {
//   def connect(net: Option[NICIOvonly], clock: Clock, reset: Bool) {
//     net.foreach { netio =>
//       val sim = Module(new SimNetwork)
//       sim.io.clock := clock
//       sim.io.reset := reset
//       sim.io.net <> netio
//     }
//   }
// }
