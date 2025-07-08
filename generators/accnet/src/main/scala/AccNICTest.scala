// package accnet

// import chisel3._
// import chisel3.util._

// import org.chipsalliance.cde.config.Parameters
// import freechips.rocketchip.devices.tilelink.TLROM
// import freechips.rocketchip.diplomacy._
// import freechips.rocketchip.tilelink._
// import freechips.rocketchip.unittest.{UnitTest, UnitTestIO}
// import freechips.rocketchip.util.{LatencyPipe, TwoWayCounter, UIntIsOneOf}
// import scala.math.max
// import AccNetConsts._


// class AccNicBareRxDriver(recvReqs: Seq[Int], recvData: Seq[BigInt])
//     (implicit p: Parameters) extends LazyModule {
//   val node = TLClientNode(Seq(TLMasterPortParameters.v1(Seq(TLClientParameters(
//     name = "test-recv-driver", sourceId = IdRange(0, 1))))))

//   lazy val module = new Impl
//   class Impl extends LazyModuleImp(this) {
//     val io = IO(new Bundle with UnitTestIO)

//     val (tl, edge) = node.out(0)
//     val dataBits = tl.params.dataBits
//     val beatBytes = dataBits / 8
//     val byteAddrBits = log2Ceil(beatBytes)

//     val (s_start :: s_recv :: s_wait ::
//          s_check_req :: s_check_resp :: s_done :: Nil) = Enum(6)
//     val state = RegInit(s_start)

//     val recvReqVec  = VecInit(recvReqs.map(_.U(NET_IF_WIDTH.W)))
//     val recvDataVec = VecInit(recvData.map(_.U(dataBits.W)))

//     val reqIdx = Reg(UInt(log2Ceil(recvReqs.size).W))
//     val memIdx = Reg(UInt(log2Ceil(recvData.size).W))

//     tl.a.valid := state === s_check_req
//     tl.a.bits := edge.Get(
//       fromSource = 0.U,
//       toAddress = recvReqVec.head + (memIdx << byteAddrBits.U),
//       lgSize = byteAddrBits.U)._2
//     tl.d.ready := state === s_check_resp

//     io.finished := state === s_done

//     when (state === s_start && io.start) {
//       reqIdx := 0.U
//       memIdx := 0.U
//       state := s_recv
//     }

//     // when (io.recv.req.fire) {
//     //   reqIdx := reqIdx + 1.U
//     //   when (reqIdx === (recvReqVec.size - 1).U) {
//     //     reqIdx := 0.U
//     //     state := s_wait
//     //   }
//     // }

//     when (state === s_wait) {
//       state := s_check_req
//     }

//     when (state === s_check_req && tl.a.ready) {
//       state := s_check_resp
//     }

//     when (state === s_check_resp && tl.d.valid) {
//       memIdx := memIdx + 1.U
//       state := s_check_req
//       when (memIdx === (recvData.size - 1).U) {
//         memIdx := 0.U
//         state := s_done
//       }
//     }

//     assert(!tl.d.valid || tl.d.bits.data === recvDataVec(memIdx),
//       "AccNicTest: Received wrong data")
  
//   }
// }

// class AccNicBareRxTest(implicit p: Parameters) extends NICLazyModule {
//   val recvReqs = Seq(0, 1440, 1464)
//   val recvLens = Seq(180, 3, 90, 7)
//   val testData = Seq.tabulate(280) { i => BigInt(i << 4) }
//   val recvData = testData.take(183) ++ testData.drop(273)

//   val nicParams = p.alterPartial({
//     case AccNICKey => Some(p(AccNICKey).get.copy(inBufFlits = 200))
//   })
//   val recvDriver = LazyModule(new AccNicBareRxDriver(recvReqs, testData))
//   val rxEngine = LazyModule(
//     new RxEngine(AccNicControllerParams(address = 0x0, beatBytes = NET_IF_BYTES))
//   )
//   val xbar = LazyModule(new TLXbar)
//   val mem = LazyModule(new TLRAM(
//     AddressSet(0, 0x1ff), beatBytes = NET_IF_BYTES))

//   val NET_LATENCY = 64
//   val MEM_LATENCY = 32
//   val RLIMIT_INC = 1
//   val RLIMIT_PERIOD = 0
//   val RLIMIT_SIZE = 8

//   xbar.node := recvDriver.node
//   xbar.node := rxEngine.mmionode
//   mem.node := TLFragmenter(NET_IF_BYTES, maxAcquireBytes) := TLBuffer.chainNode(MEM_LATENCY) := xbar.node


//   lazy val module = new Impl
//   class Impl extends LazyModuleImp(this) {
//     val io = IO(new Bundle with UnitTestIO)

//     val gen = Module(new PacketGen(recvLens, testData))
//     gen.io.start := io.start
//     recvDriver.module.io.start := gen.io.finished
//     // rxEngine.module.io.recv <> recvDriver.module.io.recv
//     rxEngine.module.io.ext <> RateLimiter(
//       gen.io.out, RLIMIT_INC, RLIMIT_PERIOD, RLIMIT_SIZE)
//     io.finished := recvDriver.module.io.finished
//   }
// }

// class AccNicBareRxTestWrapper(implicit p: Parameters) extends UnitTest(20000) {
//   val test = Module(LazyModule(new AccNicBareRxTest).module)
//   test.io.start := io.start
//   io.finished := test.io.finished
// }