package space.spacelift.mq.proxy

import akka.actor.{Props, Actor, ActorSystem}
import akka.pattern.ask
import akka.testkit.{ImplicitSender, TestKit}
import space.spacelift.amqp.Amqp.AddBinding
import space.spacelift.amqp.Amqp.ChannelParameters
import space.spacelift.amqp.Amqp.ExchangeParameters
import space.spacelift.amqp.Amqp.QueueParameters
import space.spacelift.amqp.Amqp._
import space.spacelift.amqp.{Amqp, ConnectionOwner}
import com.rabbitmq.client.ConnectionFactory
import concurrent.duration._
import concurrent.{Await, Future, ExecutionContext}
import java.util.concurrent.TimeUnit
import org.junit.runner.RunWith
import org.scalatest.WordSpecLike
import org.scalatest.junit.JUnitRunner
import org.scalatest.Matchers
import serializers.JsonSerializer

object RemoteJsonCallTest {
  case class AddRequest(x: Int, y: Int)
  case class AddResponse(x: Int, y: Int, sum: Int)
}

@RunWith(classOf[JUnitRunner])
class RemoteJsonCallTest extends TestKit(ActorSystem("TestSystem")) with ImplicitSender with WordSpecLike with Matchers {

  import RemoteJsonCallTest._

  "AMQP Proxy" should {
    "handle JSON calls" in {
      import ExecutionContext.Implicits.global
      val connFactory = new ConnectionFactory()
      val conn = system.actorOf(Props(new ConnectionOwner(connFactory)), name = "conn")
      val exchange = ExchangeParameters(name = "amq.direct", exchangeType = "", passive = true)
      val queue = QueueParameters(name = "calculator-json", passive = false, autodelete = true)

      // create a simple calculator actor
      val calc = system.actorOf(Props(new Actor() {
        def receive = {
          case AddRequest(x, y) => sender ! AddResponse(x, y, x + y)
        }
      }))
      // create an AMQP proxy server which consumes messages from the "calculator" queue and passes
      // them to our Calculator actor
      val server = ConnectionOwner.createChildActor(conn, RpcServer.props(new AmqpProxy.ProxyServer(calc), channelParams = Some(ChannelParameters(qos = 1))))
      Amqp.waitForConnection(system, server).await(5, TimeUnit.SECONDS)

      server ! AddBinding(Binding(exchange, queue, "calculator-json"))
      expectMsgPF() {
        case Amqp.Ok(AddBinding(_), _) => true
      }

      // create an AMQP proxy client in front of the "calculator queue"
      val client = ConnectionOwner.createChildActor(conn, RpcClient.props())
      val proxy = system.actorOf(
        AmqpProxy.ProxyClient.props(client, "amq.direct", "calculator-json", JsonSerializer),
        name = "proxy")

      Amqp.waitForConnection(system, client).await(5, TimeUnit.SECONDS)
      implicit val timeout: akka.util.Timeout = 5 seconds

      val futures = for (x <- 0 until 5; y <- 0 until 5) yield (proxy ? AddRequest(x, y)).mapTo[AddResponse]
      val result = Await.result(Future.sequence(futures), 5 seconds)
      assert(result.length === 25)
      assert(result.filter(r => r.sum != r.x + r.y).isEmpty)
    }
  }
}
