package accnet

import chisel3._
import chisel3.util._

// These classes originated in testchipip, but are copied
// here to reduce dependencies
class StreamChannel(val w: Int) extends Bundle {
  val data = UInt(w.W)
  val keep = UInt((w/8).W)
  val last = Bool()
}

class StreamChannelFull(val w: Int) extends Bundle {
  val data = UInt(w.W)
  val keep = UInt((w/8).W)
  val last = Bool()
  val user = Bool()
  val valid = Bool()
  val ready = Flipped(Bool())
}

class StreamIO(val w: Int) extends Bundle {
  val in = Flipped(Decoupled(new StreamChannel(w)))
  val out = Decoupled(new StreamChannel(w))

  def flipConnect(other: StreamIO) {
    in <> other.out
    other.in <> out
  }
}
