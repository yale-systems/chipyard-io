package accnet

import chisel3._
import freechips.rocketchip.subsystem.BaseSubsystemConfig
import org.chipsalliance.cde.config.{Parameters, Config}
import freechips.rocketchip.unittest.UnitTests

class WithAccNetUnitTests extends Config((site, here, up) => {
  case AccNICKey => Some(NICConfig())
  case UnitTests => (p: Parameters) => {
    Seq(
      Module(new NetworkPacketBufferTest),
      Module(new PauserTest),
      Module(new NetworkTapTest),
      Module(new RateLimiterTest),
      Module(new AlignerTest),
      Module(new ChecksumTest),
      Module(new ChecksumTCPVerify),
      Module(new AccNicSendTestWrapper()(p)),
      Module(new AccNicRecvTestWrapper()(p)),
      Module(new AccNicTestWrapper()(p)),
      Module(new MisalignedTestWrapper()(p)))
  }
})

class AccNetUnitTestConfig extends Config(
  new WithAccNetUnitTests ++ new BaseSubsystemConfig)

class WithAccNIC(inBufFlits: Int = 1800, usePauser: Boolean = false, ctrlQueueDepth: Int = 64)
    extends Config((site, here, up) => {
  case AccNICKey => Some(NICConfig(
    inBufFlits = inBufFlits,
    ctrlQueueDepth = ctrlQueueDepth,
    usePauser = usePauser,
    checksumOffload = true))
})

class WithNoAccNIC extends Config((site, here, up) => {
  case AccNICKey => None
})
