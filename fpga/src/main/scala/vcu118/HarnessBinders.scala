package chipyard.fpga.vcu118

import chisel3._
import chisel3.experimental.{BaseModule}

import org.chipsalliance.diplomacy.nodes.{HeterogeneousBag}
import freechips.rocketchip.tilelink.{TLBundle}

import sifive.blocks.devices.uart.{UARTPortIO}
import sifive.blocks.devices.spi.{HasPeripherySPI, SPIPortIO}

import chipyard._
import chipyard.harness._
import chipyard.iobinders._
import accnet.{AccNicQSFP}

/*** UART ***/
class WithUART extends HarnessBinder({
  case (th: VCU118FPGATestHarnessImp, port: UARTPort, chipId: Int) => {
    th.vcu118Outer.io_uart_bb.bundle <> port.io
  }
})

/*** QSFP1 ***/
class WithQSFPAccNIC extends HarnessBinder({
  case (th: VCU118FPGATestHarnessImp, port: AccNICPort, chipId: Int) => {
    // th.vcu118Outer.io_qsfp1_bb.bundle <> port.io
    // withClock(port.io.clock) { AccNicQSFP.connect(port.io.bits, port.params) }
    val qsfp_io = th.vcu118Outer.qsfpPlacedOverlay.overlayOutput.qsfp.getWrappedValue
    
    val accIO = Wire(new accnet.FlippedQSFPIO)
    // // Direct connections for UInt(4.W) signals
    qsfp_io.tx_p := accIO.tx_p 
    qsfp_io.tx_n := accIO.tx_n 
    accIO.rx_p := qsfp_io.rx_p 
    accIO.rx_n := qsfp_io.rx_n 
    // Direct connections for scalar signals
    accIO.mgt_refclk_p := qsfp_io.mgt_refclk_p 
    accIO.mgt_refclk_n := qsfp_io.mgt_refclk_n 
    qsfp_io.modsell := accIO.modsell 
    qsfp_io.resetl := accIO.resetl  
    accIO.modprsl := qsfp_io.modprsl 
    accIO.intl := qsfp_io.intl    
    qsfp_io.lpmode := accIO.lpmode   

    // val accIO = Wire(new accnet.FlippedQSFPIO)
    // accIO := qsfp_io.asUInt.asTypeOf(new accnet.FlippedQSFPIO)

    withClock(th.childClock) {
      val port_bits = Some(port.io.bits)
      AccNicQSFP.connect(accIO, port_bits, port.params, th.childClock, th.childReset.asBool, th.referenceClockFreqMHz) 
    }
  }
})

/*** SPI ***/
class WithSPISDCard extends HarnessBinder({
  case (th: VCU118FPGATestHarnessImp, port: SPIPort, chipId: Int) => {
    th.vcu118Outer.io_spi_bb.bundle <> port.io
  }
})

/*** Experimental DDR ***/
class WithDDRMem extends HarnessBinder({
  case (th: VCU118FPGATestHarnessImp, port: TLMemPort, chipId: Int) => {
    val bundles = th.vcu118Outer.ddrClient.out.map(_._1)
    val ddrClientBundle = Wire(new HeterogeneousBag(bundles.map(_.cloneType)))
    bundles.zip(ddrClientBundle).foreach { case (bundle, io) => bundle <> io }
    ddrClientBundle <> port.io
  }
})

class WithJTAG extends HarnessBinder({
  case (th: VCU118FPGATestHarnessImp, port: JTAGPort, chipId: Int) => {
    val jtag_io = th.vcu118Outer.jtagPlacedOverlay.overlayOutput.jtag.getWrappedValue
    port.io.TCK := jtag_io.TCK
    port.io.TMS := jtag_io.TMS
    port.io.TDI := jtag_io.TDI
    jtag_io.TDO.data := port.io.TDO
    jtag_io.TDO.driven := true.B
    // ignore srst_n
    jtag_io.srst_n := DontCare

  }
})
