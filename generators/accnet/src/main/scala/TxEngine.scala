package accnet

import chisel3._
import chisel3.util._
import chisel3.reflect.DataMirror
import org.chipsalliance.cde.config.{Field, Parameters}
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.regmapper._
import freechips.rocketchip.tilelink._
import freechips.rocketchip.util._
import AccNetConsts._

class AccNicSendIO extends Bundle {
  val req  = Decoupled(UInt(64.W))             // [63:16] addr, [15:1] length, [0] partial
  val comp = Flipped(Decoupled(Bool()))        // completion notification
}

class TxEngine(c: AccNicControllerParams)(implicit p: Parameters)
  extends RegisterRouter(RegisterRouterParams(
    name = "accnic-TxEngine",
    compat = Seq("yale-systems,acc-nic-tx-engine"),
    base = c.address,
    beatBytes = c.beatBytes
  )) with HasTLControlRegMap
  with HasNICParameters {

  val reader = LazyModule(new StreamReader(nMemXacts, outBufFlits, maxAcquireBytes))

  def tlRegmap(mapping: RegField.Map*): Unit = regmap(mapping: _*)

  lazy val module = new Impl

  class Impl extends LazyModuleImp(this) {
    val io = IO(new Bundle {
      val ext       = Decoupled(new StreamChannel(NET_IF_WIDTH))
      val interrupt = Output(Bool())
    })

    val txReqQ = Module(new Queue(UInt(64.W), 64)) // [63]=partial, [62:48]=len, [47:0]=addr  
    val readerMod = reader.module
    val streaming = RegInit(false.B)


    val reqAddr    = txReqQ.io.deq.bits(47, 0)
    val reqLen     = txReqQ.io.deq.bits(62, 48)
    val reqPartial = txReqQ.io.deq.bits(63)

    txReqQ.io.deq.ready := readerMod.io.req.fire
    readerMod.io.req.valid := txReqQ.io.deq.valid
    readerMod.io.req.bits.address := reqAddr
    readerMod.io.req.bits.length  := reqLen
    readerMod.io.req.bits.partial := reqPartial


    when (readerMod.io.req.fire) {
      printf(p"[TX-ENGINE] Start DMA: addr = 0x${Hexadecimal(reqAddr)}, len = ${reqLen}, partial = ${reqPartial}\n")
      streaming := true.B
    }

    io.ext <> readerMod.io.out

    // readerMod.io.resp.ready := true.B

    // io.ext.valid := readerMod.io.out.valid
    // io.ext.bits  := readerMod.io.out.bits
    // readerMod.io.out.ready := io.ext.ready

    val interruptPending = RegInit(false.B)
    val interruptClear   = RegInit(false.B)

    readerMod.io.resp.ready := true.B // Always ready to receive response
    when (readerMod.io.resp.fire) {
      interruptPending := true.B
      printf("[TX-ENGINE] DMA completed, raising interrupt.\n")
    }

    when (interruptClear) {
      interruptPending := false.B
      interruptClear := false.B
      printf("[TX-ENGINE] Interrupt cleared by software.\n")
    }

    io.interrupt := interruptPending

    // Logging MMIO write to enqueue
    when (txReqQ.io.enq.fire) {
      val addr = txReqQ.io.enq.bits(47, 0)
      val len  = txReqQ.io.enq.bits(62, 48)
      val part = txReqQ.io.enq.bits(63)
      printf(p"[TX-ENGINE] Enqueue DMA req: addr = 0x${Hexadecimal(addr)}, len = ${len}, partial = ${part}\n")
    }

    val qDepth = ctrlQueueDepth
    require(qDepth < (1 << 8))

    val sendCompDown = WireInit(false.B)
    val txCompCount = TwoWayCounter(readerMod.io.resp.fire, sendCompDown, qDepth)
    val sendCompValid = txCompCount > 0.U

    def sendCompRead = (ready: Bool) => {
      sendCompDown := sendCompValid && ready
      (sendCompValid, true.B)
    }

    // count number of sends completed

    tlRegmap(
      0x00 -> Seq(RegField.r(16, txReqQ.io.count)),
      0x08 -> Seq(RegField.w(64, txReqQ.io.enq)),
      0x10 -> Seq(RegField.r(16, txCompCount)),
      0x12 -> Seq(RegField.r(1, sendCompRead)),
      0x18 -> Seq(RegField(1, interruptPending)),
      0x1C -> Seq(RegField(1, interruptClear))
    )
  }
}
