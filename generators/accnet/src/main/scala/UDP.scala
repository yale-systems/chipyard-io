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

class Headers extends Bundle {
  // Ethernet Header
  val eth_dst = UInt(48.W)
  val eth_src = UInt(48.W)
  val eth_type = UInt(16.W)
  // IP Header
  val ip_version = UInt(4.W)
  val ip_ihl = UInt(4.W)
  val ip_tos = UInt(8.W)
  val ip_len = UInt(16.W)
  val ip_id = UInt(16.W)
  val ip_flags = UInt(3.W)
  val ip_offset = UInt(13.W)
  val ip_ttl = UInt(8.W)
  val ip_proto = UInt(8.W)
  val ip_chksum = UInt(16.W)
  val ip_src = UInt(32.W)
  val ip_dst = UInt(32.W)
  // UDP Header
  val src_port = UInt(16.W)
  val dst_port = UInt(16.W)
  val udp_length = UInt(16.W)
  val udp_checksum = UInt(16.W)
}

class ParsedUDPHeader extends Bundle {
  val dstPort = UInt(16.W)
  val length = UInt(16.W)
  val payloadStart = UInt(8.W) // offset in flits
}

class UDPHeaderExtractor extends Module {
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new StreamChannel(NET_IF_WIDTH)))
    val out = Decoupled(new StreamChannel(NET_IF_WIDTH))
    val udpHeader = Valid(new ParsedUDPHeader)
  })

  val sIdle :: sParse :: sPass :: Nil = Enum(3)
  val state = RegInit(sIdle)

  val header = Reg(new ParsedUDPHeader)
  val flitCount = RegInit(0.U(8.W))

  io.in.ready := (state === sIdle || state === sParse) && io.out.ready
  io.out.valid := state === sPass && io.in.valid
  io.out.bits := io.in.bits
  io.udpHeader.valid := false.B
  io.udpHeader.bits := header

  switch(state) {
    is(sIdle) {
      when(io.in.valid) {
        flitCount := 0.U
        state := sParse
      }
    }
    is(sParse) {
      when(io.in.valid) {
        flitCount := flitCount + 1.U

        // Simplified assumption: UDP header is in flit 3
        when(flitCount === 2.U) {
          val data = io.in.bits.data
          header.dstPort := data(31, 16)
          header.length := data(63, 48)
          header.payloadStart := 4.U // e.g., 4th flit is payload start
          io.udpHeader.valid := true.B
          state := sPass
        }
      }
    }
    is(sPass) {
      when(io.in.bits.last && io.in.valid && io.out.ready) {
        state := sIdle
      }
    }
  }
}

class UDPWriter(nMemXacts: Int, maxAcquireBytes: Int)(implicit p: Parameters) extends LazyModule {
  val writer = LazyModule(new StreamWriter(nMemXacts, maxAcquireBytes))
  val node = writer.node

  lazy val module = new LazyModuleImp(this) {
    val io = IO(new Bundle {
      val in = Flipped(Decoupled(new StreamChannel(NET_IF_WIDTH)))
      val udpHeader = Flipped(Valid(new ParsedUDPHeader))
      val config = Input(new Bundle {
        val dstPort = UInt(16.W)
        val memBase = UInt(64.W)
        val offset = UInt(32.W)
      })
    })

    val baseAddr = Reg(UInt(64.W))
    val offset = Reg(UInt(32.W))
    val streaming = RegInit(false.B)

    val shouldWrite = io.udpHeader.valid && (io.udpHeader.bits.dstPort === io.config.dstPort)
    when(io.udpHeader.valid && shouldWrite) {
      baseAddr := io.config.memBase + io.config.offset
      offset := io.config.offset + io.udpHeader.bits.length // wraparound if needed
      streaming := true.B
    }

    writer.module.io.req.valid := streaming
    writer.module.io.req.bits.address := baseAddr
    writer.module.io.req.bits.length := io.udpHeader.bits.length

    writer.module.io.in.valid := io.in.valid && streaming
    writer.module.io.in.bits := io.in.bits
    io.in.ready := writer.module.io.in.ready && streaming

    when(io.in.fire && io.in.bits.last) {
      streaming := false.B
    }
  }
}