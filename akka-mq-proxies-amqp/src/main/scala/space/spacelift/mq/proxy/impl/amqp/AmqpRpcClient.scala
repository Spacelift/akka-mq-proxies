package space.spacelift.mq.proxy.impl.amqp

import akka.actor.{ActorRef, Props}
import akka.event.LoggingReceive
import com.rabbitmq.client.AMQP.BasicProperties
import com.rabbitmq.client.{Channel, DefaultConsumer, Envelope}
import space.spacelift.amqp.Amqp._
import space.spacelift.amqp.ChannelOwner
import space.spacelift.mq.proxy.patterns.RpcClient
import space.spacelift.mq.proxy.{Delivery => ProxyDelivery}

import scala.concurrent.ExecutionContext

object AmqpRpcClient {
  def props(
             exchange: ExchangeParameters,
             routingKey: String,
             replyQueue: Option[QueueParameters] = None,
             channelParams: Option[ChannelParameters] = None
           ): Props =
    Props(new AmqpRpcClient(exchange, routingKey, true, false, replyQueue, channelParams))

  def props(
             exchange: ExchangeParameters,
             routingKey: String,
             replyQueue: QueueParameters,
             channelParams: ChannelParameters
           )(implicit ctx: ExecutionContext): Props =
    props(exchange, routingKey, replyQueue = Some(replyQueue), channelParams = Some(channelParams))

  def props(exchange: ExchangeParameters, routingKey: String, replyQueue: QueueParameters)(implicit ctx: ExecutionContext): Props =
    props(exchange, routingKey, Some(replyQueue))
}

class AmqpRpcClient(
                     exchange: ExchangeParameters,
                     routingKey: String,
                     mandatory: Boolean,
                     immediate: Boolean,
                     replyQueue: Option[QueueParameters],
                     channelParams: Option[ChannelParameters] = None
                   ) extends ChannelOwner(channelParams = channelParams) with RpcClient {
  import RpcClient._

  var queue: String = ""
  var consumer: Option[DefaultConsumer] = None

  override def onChannel(channel: Channel, forwarder: ActorRef) {
    super.onChannel(channel, forwarder)

    if (replyQueue.isDefined) {
      queue = declareQueue(channel, replyQueue.get).getQueue
    } else {
      // create a private, exclusive reply queue; its name will be randomly generated by the broker
      queue = declareQueue(channel, QueueParameters("", passive = false, exclusive = true)).getQueue
    }

    log.debug(s"setting consumer on private queue $queue")
    consumer = Some(new DefaultConsumer(channel) {
      override def handleDelivery(consumerTag: String, envelope: Envelope, properties: BasicProperties, body: Array[Byte]) {
        self ! Delivery(consumerTag, envelope, properties, body)
      }
    })
    channel.basicConsume(queue, false, consumer.get)
    correlationMap.clear()
  }

  override def disconnected: Receive = LoggingReceive ({
    case request@Request(publish, numberOfResponses) => {
      log.warning(s"not connected, cannot send rpc request")
    }
  }: Receive) orElse super.disconnected

  override def connected(channel: Channel, forwarder: ActorRef): Receive = LoggingReceive({
    case Request(publish, numberOfResponses) => {
      counter = counter + 1
      log.debug(s"sending ${publish.size} messages, replyTo = $queue")
      publish.foreach(p => {
        val props = new BasicProperties.Builder()
          .correlationId(counter.toString)
          .replyTo(queue)
          .contentType(p.properties.contentType)
          .contentEncoding(p.properties.clazz)
          .build()

        channel.basicPublish(exchange.name, routingKey, mandatory, immediate, props, p.body)
      })
      if (numberOfResponses > 0) {
        correlationMap += (counter.toString -> RpcResult(sender, numberOfResponses, collection.mutable.ListBuffer.empty[ProxyDelivery]))
      }
    }
    case delivery@Delivery(consumerTag: String, envelope: Envelope, properties: BasicProperties, body: Array[Byte]) => {
      channel.basicAck(envelope.getDeliveryTag, false)
      correlationMap.get(properties.getCorrelationId) match {
        case Some(results) => {
          results.deliveries += AmqpProxy.deliveryToProxyDelivery(delivery)
          if (results.deliveries.length == results.expected) {
            results.destination ! Response(results.deliveries.toList)
            correlationMap -= properties.getCorrelationId
          }
        }
        case None => log.warning("unexpected message with correlation id " + properties.getCorrelationId)
      }
    }
    case msg@ReturnedMessage(replyCode, replyText, exchange, routingKey, properties, body) => {
      correlationMap.get(properties.getCorrelationId) match {
        case Some(results) => {
          results.destination ! RpcClient.Undelivered(msg)
          correlationMap -= properties.getCorrelationId
        }
        case None => log.warning("unexpected returned message with correlation id " + properties.getCorrelationId)
      }
    }
  }: Receive) orElse super.connected(channel, forwarder)
}
