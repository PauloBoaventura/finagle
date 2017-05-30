package com.twitter.finagle.loadbalancer.aperture

import com.twitter.conversions.time._
import com.twitter.finagle.Address
import com.twitter.finagle.loadbalancer.{EndpointFactory, LazyEndpointFactory}
import com.twitter.finagle.ServiceFactoryProxy
import com.twitter.finagle.stats.{StatsReceiver, InMemoryStatsReceiver}
import com.twitter.util._
import org.scalatest.fixture.FunSuite

class ExpirationTest extends FunSuite with ApertureSuite {
  /**
   * An aperture load balancer which mixes in expiration but no
   * controller or load metric. We manually have to adjust the
   * aperture to test for nodes falling in and out of the window.
   */
  private class ExpiryBal(
      val idleTime: Duration = 1.minute,
      val mockTimer: MockTimer = new MockTimer,
      val stats: InMemoryStatsReceiver = new InMemoryStatsReceiver)
    extends TestBal
    with Expiration[Unit, Unit] {

    def expired: Int = stats.counters(Seq("expired"))
    def noExpired: Boolean = stats.counters.get(Seq("expired")).isEmpty

    protected def endpointIdleTime: Duration = idleTime / 2
    protected def statsReceiver: StatsReceiver = stats

    private[this] val expiryTask = newExpiryTask(mockTimer)

    case class Node(factory: EndpointFactory[Unit, Unit])
      extends ServiceFactoryProxy[Unit, Unit](factory)
      with ExpiringNode
      with ApertureNode {
      def load: Double = 0
      def pending: Int = 0
      override val token: Int = 0
    }

    protected def newNode(factory: EndpointFactory[Unit, Unit]): Node = Node(factory)
    protected def failingNode(cause: Throwable): Node = ???

    override def close(when: Time) = {
      expiryTask.cancel()
      super.close(when)
    }
  }

  private def newLazyEndpointFactory(sf: Factory) =
    new LazyEndpointFactory(() => sf, Address.Failed(new Exception))

  case class FixtureParam(tc: TimeControl)
  def withFixture(test: OneArgTest) =
    Time.withCurrentTimeFrozen { tc => test(FixtureParam(tc)) }

  test("does not expire uninitialized nodes") { f =>
    val bal = new ExpiryBal
    val ep0, ep1= Factory(0)
    bal.update(Vector(ep0, ep1))
    assert(bal.aperturex == 1)

    f.tc.advance(bal.idleTime)
    bal.mockTimer.tick()
    assert(bal.noExpired)
  }

  test("expires nodes outside of aperture") { f =>
    val bal = new ExpiryBal

    val eps = Vector.tabulate(10) { i => Factory(i) }
    bal.update(eps.map(newLazyEndpointFactory))
    bal.adjustx(eps.size)
    assert(bal.aperturex == eps.size)

    // we rely on p2c to ensure that each endpoint gets
    // a request for service acquisition.
    def checkoutLoop(): Unit = (0 to 100).foreach { _ =>
      Await.result(bal()).close()
    }

    checkoutLoop()
    assert(eps.filter(_.total > 0).size == eps.size)

    // since our aperture covers all nodes no endpoint should go idle
    f.tc.advance(bal.idleTime)
    bal.mockTimer.tick()
    assert(bal.noExpired)

    eps.foreach(_.clear())
    // set idle time on each node.
    checkoutLoop()
    // shrink aperture so some nodes qualify for expiration.
    bal.adjustx(-eps.size / 2)
    // tick the timer partially and no expirations
    f.tc.advance(bal.idleTime / 4)
    bal.mockTimer.tick()
    assert(bal.noExpired)
    // tick time fully
    f.tc.advance(bal.idleTime)
    bal.mockTimer.tick()
    assert(bal.expired == eps.size / 2)
    assert(eps.map(_.numCloses).sum == eps.size / 2)
  }

  test("idle time measured only on last response") { f =>
    val bal = new ExpiryBal
    val eps = Vector(Factory(0), Factory(1))
    bal.update(eps)
    bal.adjustx(1)
    assert(bal.aperturex == 2)

    val svcs = for (_ <- 0 until 101) yield { Await.result(bal()) }
    bal.adjustx(-1)
    assert(bal.aperturex == 1)
    assert(eps.map(_.outstanding).sum == 101)
    // Note, this assumes that `svcs.last` was acquired from
    // the factory that sits outside the aperture. This is true
    // with the current params since we fix the rng, but may
    // not always be true if we change the number of svc
    // acquisition requests.
    for (svc <- svcs.init) {
      Await.result(svc.close())
      f.tc.advance(bal.idleTime)
      bal.mockTimer.tick()
      assert(bal.noExpired)
    }

    assert(eps.map(_.outstanding).sum == 1)
    Await.result(svcs.last.close())
    assert(eps.map(_.outstanding).sum == 0)

    f.tc.advance(bal.idleTime)
    bal.mockTimer.tick()
    assert(bal.expired == 1)
  }
}