import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

class TxEngineTest extends AnyFlatSpec with ChiselScalatestTester {
  behavior of "TxEngine"

  it should "send a packet correctly" in {
    test(new TxEngine(/* parameters */)) { c =>
      // Stimulate inputs
      c.io.in.valid.poke(true.B)
      c.io.in.bits.data.poke("hdeadbeef".U)
      c.io.in.bits.last.poke(true.B)

      // Wait for handshake
      while (!c.io.in.ready.peek().litToBoolean) {
        c.clock.step()
      }

      // Check output (DMA stream, interrupt, etc.)
      c.clock.step(10)
      c.io.done.expect(true.B)
    }
  }
}
