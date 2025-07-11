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

class AccNicRecvIO extends Bundle {
  val req = Decoupled(UInt(48.W))
  val comp = Flipped(Decoupled(UInt(NET_LEN_BITS.W)))
}

class AccNicWriter(implicit p: Parameters) extends NICLazyModule {
  val writer = LazyModule(new StreamWriter(nMemXacts, maxAcquireBytes))
  val node = writer.node

  lazy val module = new Impl
  class Impl extends LazyModuleImp(this) {
    val io = IO(new Bundle {
      val recv = Flipped(new AccNicRecvIO)
      val in = Flipped(Decoupled(new StreamChannel(NET_IF_WIDTH)))
      val length = Flipped(Valid(UInt(NET_LEN_BITS.W)))
    })

    val streaming = RegInit(false.B)
    val helper = DecoupledHelper(
      io.recv.req.valid,
      writer.module.io.req.ready,
      io.length.valid,
      !streaming
    )

    writer.module.io.req.valid := helper.fire(writer.module.io.req.ready)
    writer.module.io.req.bits.address := io.recv.req.bits
    writer.module.io.req.bits.length := io.length.bits
    io.recv.req.ready := helper.fire(io.recv.req.valid)

    writer.module.io.in.valid := io.in.valid && streaming
    writer.module.io.in.bits := io.in.bits
    io.in.ready := writer.module.io.in.ready && streaming

    io.recv.comp <> writer.module.io.resp

    when (io.recv.req.fire) {
       printf(p"[RX-WRITER] Received DMA request: addr = 0x${Hexadecimal(io.recv.req.bits)} len = ${io.length.bits}\n")
      streaming := true.B
    }
    when (io.in.fire && io.in.bits.last) {
       printf("[RX-WRITER] Finished streaming packet into memory.\n")
      streaming := false.B
    }
  }
}

class RxEngine(c: AccNicControllerParams)(implicit p: Parameters)
  extends RegisterRouter(RegisterRouterParams(
    name = "accnic-RxEngine",
    compat = Seq("yale-systems,acc-nic"),
    base = c.address,
    beatBytes = c.beatBytes
  )) with HasTLControlRegMap
  with HasNICParameters {

  val writer = LazyModule(new AccNicWriter)
  val mmionode = writer.node


  def tlRegmap(mapping: RegField.Map*): Unit = regmap(mapping:_*)

  lazy val module = new Impl
  class Impl extends LazyModuleImp(this) {
    val io = IO(new Bundle {
      val ext = Flipped(Decoupled(new StreamChannel(NET_IF_WIDTH)))
      val interrupt = Output(Bool())
    })

    val dmaAddrRing = Module(new Queue(UInt(48.W), 64))
    val packetBuffer = Module(new NetworkPacketBuffer(inBufFlits, maxBytes = packetMaxBytes))

    packetBuffer.io.stream.in <> io.ext

    

    // val lengthFifo = Module(new Queue(UInt(NET_LEN_BITS.W), 64))
    // lengthFifo.io.enq.valid := packetBuffer.io.length.valid
    // lengthFifo.io.enq.bits := packetBuffer.io.length.bits

    val writerMod = writer.module
    val streaming = RegInit(false.B)
    val addr = Reg(UInt(48.W))
    val length = Reg(UInt(48.W))

    

    val bufout = packetBuffer.io.stream.out
    val buflen = packetBuffer.io.length

    writerMod.io.in <> bufout
    writerMod.io.length.valid := buflen.valid
    writerMod.io.length.bits  := buflen.bits

    writerMod.io.recv.req <> Queue(dmaAddrRing.io.deq, 1)

    // val startDmaHelper = DecoupledHelper(
    //   dmaAddrRing.io.deq.valid,
    //   lengthFifo.io.deq.valid,
    //   writerMod.io.recv.req.ready,
    //   !streaming
    // )

    // writerMod.io.recv.req.valid := startDmaHelper.fire(writerMod.io.recv.req.ready)
    // writerMod.io.recv.req.bits := dmaAddrRing.io.deq.bits
    // dmaAddrRing.io.deq.ready := writerMod.io.recv.req.fire

    // writerMod.io.recv.comp.ready := true.B

    // writerMod.io.length.valid := startDmaHelper.fire(writerMod.io.recv.req.ready)
    // writerMod.io.length.bits := lengthFifo.io.deq.bits
    // lengthFifo.io.deq.ready := writerMod.io.length.fire

    // writerMod.io.in.valid := packetBuffer.io.stream.out.valid && streaming
    // writerMod.io.in.bits := packetBuffer.io.stream.out.bits
    // packetBuffer.io.stream.out.ready := writerMod.io.in.ready && streaming

    when (writerMod.io.recv.req.fire) {
      printf(p"[RX-ENGINE] Started DMA: addr = 0x${Hexadecimal(writerMod.io.recv.req.bits)}, len = ${writerMod.io.length.bits}\n")
      streaming := true.B
      addr := writerMod.io.recv.req.bits
      length := writerMod.io.length.bits
    }
    // when (packetBuffer.io.stream.out.fire) {
    //    printf(p"[RX-ENGINE] Streaming word: data = 0x${Hexadecimal(packetBuffer.io.stream.out.bits.data)} last = ${packetBuffer.io.stream.out.bits.last}\n")
    // }
    when (packetBuffer.io.stream.out.fire && packetBuffer.io.stream.out.bits.last) {
      printf("[RX-ENGINE] Packet fully streamed, ending DMA.\n")
      streaming := false.B
    }

    val interruptPending = RegInit(false.B)
    val interruptClear   = RegInit(false.B)

    when (writerMod.io.recv.comp.fire) {
      interruptPending := true.B
      printf("[RX-ENGINE] DMA complete, setting interrupt.\n")
    }

    when (interruptClear) {
      interruptPending := false.B
      interruptClear := false.B
      printf("[RX-ENGINE] Interrupt cleared by software.\n")
    }

    io.interrupt := interruptPending

    val completionLog = Module(new Queue(UInt(64.W), 64))

    when (writerMod.io.recv.comp.valid && completionLog.io.enq.ready) {
      writerMod.io.recv.comp.ready := true.B
      completionLog.io.enq.valid := true.B
      completionLog.io.enq.bits := Cat(addr(47, 0), length(15, 0))
    } .otherwise {
      writerMod.io.recv.comp.ready := false.B
      completionLog.io.enq.valid := false.B
      completionLog.io.enq.bits := 0.U
    }

    tlRegmap(
      0x04 -> Seq(RegField.r(16, dmaAddrRing.io.count)),
      0x08 -> Seq(RegField.w(64, dmaAddrRing.io.enq)),
      0x10 -> Seq(RegField.r(64, completionLog.io.deq)),
      0x18 -> Seq(RegField.r(16, completionLog.io.count)),
      0x20 -> Seq(RegField(1, interruptPending)),
      0x24 -> Seq(RegField(1, interruptClear))
    )
  }
}
