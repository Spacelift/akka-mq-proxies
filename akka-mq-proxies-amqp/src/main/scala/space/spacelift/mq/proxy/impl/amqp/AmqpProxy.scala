package space.spacelift.mq.proxy.impl.amqp

import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import akka.pattern.{ask, pipe}
import akka.serialization.Serializer
import akka.util.Timeout
import com.rabbitmq.client.AMQP
import com.rabbitmq.client.AMQP.BasicProperties
import org.slf4j.LoggerFactory
import space.spacelift.amqp.Amqp
import space.spacelift.amqp.Amqp.{Delivery, Publish}
import space.spacelift.mq.proxy.patterns.{ProcessResult, Processor, RpcClient}
import space.spacelift.mq.proxy.serializers.JsonSerializer
import space.spacelift.mq.proxy.{ProxyException, _}

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}



object AmqpProxy extends Proxy {
  /**
   * serialize a message and return a (blob, AMQP properties) tuple. The following convention is used for the AMQP properties
   * the message will be sent with:
   * <ul>
   * <li>contentEncoding is set to the name of the serializer that is used</li>
   * <li>contentType is set to the name of the message class</li>
   * </ul>
   * @param msg input message
   * @param serializer serializer
   * @param deliveryMode AMQP delivery mode that will be included in the returned AMQP properties
   * @return a (blob, properties) tuple where blob is the serialized message and properties the AMQP properties the message
   *         should be sent with.
   */
  def serialize(msg: AnyRef, serializer: Serializer, deliveryMode: Int = 1): (Array[Byte], AMQP.BasicProperties) = {
    val (body, msgProps) = super.serialize(serializer, msg)
    val props = new BasicProperties
                  .Builder()
                  .contentEncoding(msg.getClass.getName)
                  .contentType(msgProps.contentType)
                  .deliveryMode(deliveryMode).build
    (body, props)
  }

  /**
   * deserialize a message
   * @param body serialized message
   * @param props AMQP properties, which contain meta-data for the serialized message
   * @return a (deserialized message, serializer) tuple
   * @see [[AmqpProxy.serialize( )]]
   */
  def deserialize(body: Array[Byte], props: AMQP.BasicProperties): (AnyRef, Serializer) = {
    super.deserialize(body, MessageProperties(props.getContentEncoding, props.getContentType))
  }

  def deliveryToProxyDelivery(delivery: Delivery) = {
    space.spacelift.mq.proxy.Delivery(delivery.body, MessageProperties(delivery.properties.getContentEncoding, delivery.properties.getContentType))
  }

  object ProxyClient {
    def props(client: ActorRef,
      exchange: String,
      routingKey: String,
      serializer: Serializer,
      timeout: Timeout = 30 seconds,
      mandatory: Boolean = true,
      immediate: Boolean = false,
      deliveryMode: Int = 1): Props = Props(new ProxyClient(client, exchange, routingKey, serializer, timeout, mandatory, immediate, deliveryMode))
  }

  /**
   * standard  one-request/one response proxy, which allows to write (myActor ? MyRequest).mapTo[MyResponse]
   * @param client AMQP RPC Client
   * @param exchange exchange to which requests will be sent
   * @param routingKey routing key with which requests will be sent
   * @param serializer message serializer
   * @param timeout response time-out
   * @param mandatory AMQP mandatory flag used to sent requests with; default to true
   * @param immediate AMQP immediate flag used to sent requests with; default to false; use with caution !!
   * @param deliveryMode AMQP delivery mode to sent request with; defaults to 1 (
   */
  class ProxyClient(client: ActorRef,
    exchange: String,
    routingKey: String,
    serializer: Serializer,
    timeout: Timeout = 30 seconds,
    mandatory: Boolean = true,
    immediate: Boolean = false,
    deliveryMode: Int = 1) extends Actor {

    import ExecutionContext.Implicits.global

    def receive: Actor.Receive = {
      case msg: AnyRef => {
        Try(serialize(msg, serializer, deliveryMode = deliveryMode)) match {
          case Success((body, props)) => {
            // publish the serialized message (and tell the RPC client that we expect one response)
            val publish = Publish(exchange, routingKey, body, Some(props), mandatory = mandatory, immediate = immediate)
            val future = (client ? RpcClient.Request(publish :: Nil, 1))(timeout).mapTo[AnyRef].map {
              case result : RpcClient.Response => {
                val delivery = result.deliveries(0)
                val (response, serializer) = deserialize(delivery.body, delivery.properties)
                response match {
                  case ServerFailure(message, throwableAsString) => akka.actor.Status.Failure(new ProxyException(message, throwableAsString))
                  case _ => response
                }
              }
              case undelivered : RpcClient.Undelivered => undelivered
            }

            future.pipeTo(sender)
          }
          case Failure(cause) => sender ! akka.actor.Status.Failure(new ProxyException("Serialization error", cause.getMessage))
        }
      }
    }
  }

  /**
   * "fire-and-forget" proxy, which allows to write myActor ! MyRequest
   * @param client AMQP RPC Client
   * @param exchange exchange to which requests will be sent
   * @param routingKey routing key with which requests will be sent
   * @param serializer message serializer
   * @param mandatory AMQP mandatory flag used to sent requests with; default to true
   * @param immediate AMQP immediate flag used to sent requests with; default to false; use with caution !!
   * @param deliveryMode AMQP delivery mode to sent request with; defaults to 1
   */
  class ProxySender(client: ActorRef,
    exchange: String,
    routingKey: String,
    serializer: Serializer,
    mandatory: Boolean = true,
    immediate: Boolean = false,
    deliveryMode: Int = 1) extends Actor with ActorLogging {

    def receive: Actor.Receive = {
      case Amqp.Ok(request, _) => log.debug("successfully processed request %s".format(request))
      case Amqp.Error(request, error) => log.error("error while processing %s : %s".format(request, error))
      case msg: AnyRef => {
        val (body, props) = serialize(msg, serializer, deliveryMode = deliveryMode)
        val publish = Publish(exchange, routingKey, body, Some(props), mandatory = mandatory, immediate = immediate)
        log.debug("sending %s to %s".format(publish, client))
        client ! publish
      }
    }
  }
}
