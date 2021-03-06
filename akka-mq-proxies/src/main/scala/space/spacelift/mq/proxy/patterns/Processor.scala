package space.spacelift.mq.proxy.patterns

import space.spacelift.mq.proxy.{Delivery, MessageProperties}

import scala.concurrent.Future

/**
  * represents the response to a RPC request
  *
  * @param value optional response message body; if None, nothing will be sent back ("fire and forget" pattern)
  * @param properties optional response message properties
  */
case class ProcessResult(value: Option[Array[Byte]], properties: Option[MessageProperties] = None)

/**
  * generic processor trait
  */
trait Processor {
  /**
    * process an incoming AMQP message
    *
    * @param delivery AMQP message
    * @return a Future[ProcessResult] instance
    */
  def process(delivery: Delivery): Future[ProcessResult]

  /**
    * create a message that describes why processing a request failed. You would typically serialize the exception along with
    * some context information.
    *
    * @param delivery delivery which cause process() to throw an exception
    * @param e exception that was thrown in process()
    * @return a ProcessResult instance
    */
  def onFailure(delivery: Delivery, e: Throwable): ProcessResult
}
