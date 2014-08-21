/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http

import com.typesafe.config.{ ConfigFactory, Config }
import scala.concurrent.duration._
import akka.http._
import akka.stream.scaladsl.Flow
import akka.io.IO
import akka.util.Timeout
import akka.actor.ActorSystem
import akka.pattern.ask
import akka.http.model._
import akka.util.ByteString
import HttpMethods._
import akka.stream.{ MaterializerSettings, FlowMaterializer }
import scala.concurrent.Future

object TestServer extends App {
  val testConf: Config = ConfigFactory.parseString("""
    akka.loglevel = INFO
    akka.log-dead-letters = off
    """)
  implicit val system = ActorSystem("ServerTest", testConf)
  import system.dispatcher

  def mapRequestToResponseFlow(requestFlow: Flow[HttpRequest]): Flow[HttpResponse] = {
    requestFlow.mapFuture {
      case HttpRequest(_, _, _, HttpEntity.Default(_, _, publisher), _) ⇒
        val subscriber = new DebugSubscriber[ByteString]
        publisher.subscribe(subscriber)

        //val completionFuture: Future[Unit] = subscriber.completionFuture
        val completionFuture: Future[Unit] = Future { Thread.sleep(1000) ; () }

        completionFuture.map { _ =>
          HttpResponse(entity = "Hello Bob")
        }
      case _ =>
        ???
    }
  }

  val materializer = FlowMaterializer(MaterializerSettings())

  implicit val askTimeout: Timeout = 500.millis
  val bindingFuture = IO(Http) ? Http.Bind(interface = "localhost", port = 8080)
  bindingFuture foreach {
    case Http.ServerBinding(localAddress, connectionStream) ⇒
      Flow(connectionStream).foreach {
        case Http.IncomingConnection(remoteAddress, requestProducer, responseConsumer) ⇒
          val requestFlow: Flow[HttpRequest] = Flow(requestProducer)
          val responseFlow: Flow[HttpResponse] = mapRequestToResponseFlow(requestFlow)
          responseFlow.produceTo(materializer, responseConsumer)
      }.consume(materializer)
  }

  println(s"Server online at http://localhost:8080/\nPress RETURN to stop...")

  Console.readLine()
  system.shutdown()

}

import org.reactivestreams._
import scala.concurrent._

class DebugSubscriber[T] extends Subscriber[T] {
  @volatile var subn: Subscription = _
  private val completion = Promise[Unit]()
  def completionFuture: Future[Unit] = completion.future
  override def onSubscribe(subscription: Subscription): Unit = {
    println(s"DebugSubscriber.onSubscribe()")
    subn = subscription
    subn.request(1)
  }
  override def onNext(element: T): Unit = {
    println(s"DebugSubscriber.onNext($element)")
    subn.request(1)
  }
  override def onComplete(): Unit = {
    println(s"DebugSubscriber.onComplete()")
    completion.success(())
  }
  override def onError(cause: Throwable): Unit = {
    println(s"DebugSubscriber.onError($cause)")
    completion.failure(cause)
  }
}
