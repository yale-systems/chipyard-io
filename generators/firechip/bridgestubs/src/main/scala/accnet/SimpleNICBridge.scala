// See LICENSE for license details

package firechip.bridgestubs

import chisel3._
import chisel3.util._

import org.chipsalliance.cde.config.{Parameters}

import firesim.lib.bridgeutils._

import firechip.bridgeinterfaces._


class AccNICBridge(implicit p: Parameters) extends BlackBox with Bridge[HostPortIO[AccNICBridgeTargetIO]] {
  val moduleName = "firechip.goldengateimplementations.AccSimpleNICBridgeModule"
  val io = IO(new AccNICBridgeTargetIO)
  val bridgeIO = HostPort(io)
  val constructorArg = None
  generateAnnotations()
}

object AccNICBridge {
  def apply(clock: Clock, nicIO: accnet.AccNICIO)(implicit p: Parameters): AccNICBridge = {
    val ep = Module(new AccNICBridge)
    // TODO: Check following IOs are same size/names/etc
    ep.io.nic <> nicIO
    ep.io.clock := clock
    ep
  }
}
