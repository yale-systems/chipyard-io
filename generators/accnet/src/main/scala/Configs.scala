package accnet

import chisel3._
import freechips.rocketchip.subsystem.BaseSubsystemConfig
import org.chipsalliance.cde.config.{Parameters, Config}
import freechips.rocketchip.devices.tilelink.TLROM
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.tilelink._
import freechips.rocketchip.unittest.{UnitTest, UnitTestIO}
import freechips.rocketchip.util.{LatencyPipe, TwoWayCounter, UIntIsOneOf}
import scala.math.max
import AccNetConsts._

import freechips.rocketchip.unittest.UnitTests

class WithAccNetUnitTests extends Config((site, here, up) => {
  case AccNICKey => Some(AccNICConfig())
  case UnitTests => (p: Parameters) => {
    Seq(
      Module(new NetworkPacketBufferTest),
      // Module(new PauserTest),
      // Module(new NetworkTapTest),
      // Module(new RateLimiterTest),
      // Module(new AlignerTest),
      // Module(new ChecksumTest),
      // Module(new ChecksumTCPVerify),
      // Module(new AccNicSendTestWrapper()(p)),
      // Module(new AccNicRecvTestWrapper()(p)),
      // Module(new AccNicTestWrapper()(p)),
      // Module(new MisalignedTestWrapper()(p)),
    )  
  }
})

// class WithAccNetUnitTests extends Config((site, here, up) => {
//   case AccNICKey => Some(AccNICConfig())          // keep your NIC params
//   case UnitTests => (p: Parameters) => Seq(
//     Module(new AccNicBareRxTestWrapper()(p))        // <-- add the new one
//   )
// })



class AccNetUnitTestConfig extends Config(
  new WithAccNetUnitTests ++ new BaseSubsystemConfig)

class WithAccNIC(inBufFlits: Int = 1800, ctrlQueueDepth: Int = 64)
    extends Config((site, here, up) => {
  case AccNICKey => Some(AccNICConfig(
    inBufFlits = inBufFlits,
    ctrlQueueDepth = ctrlQueueDepth))
})

class WithNoAccNIC extends Config((site, here, up) => {
  case AccNICKey => None
})
