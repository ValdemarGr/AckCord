/*
 * This file is part of AckCord, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2019 Katrix
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package ackcord

import scala.collection.immutable

import ackcord.gateway.GatewayMessage
import akka.actor.typed.ActorSystem
import akka.stream.scaladsl.{Sink, Source}
import akka.{NotUsed, actor => classic}

/**
  * Represents a cache that can be published and subscribed to.
  * @param publish A sink used for publishing. Any elements connected to this
  *                sink is published to the cache.
  * @param subscribe A source to subscribe to. All updates are pushed here.
  */
case class Cache(
    publish: Sink[CacheEvent, NotUsed],
    subscribe: Source[(CacheEvent, CacheState), NotUsed],
    gatewayPublish: Sink[GatewayMessage[Any], NotUsed],
    gatewaySubscribe: Source[GatewayMessage[Any], NotUsed]
)(implicit system: ActorSystem[Nothing]) {

  /**
    * Publish a single element to this cache.
    */
  def publish(elem: CacheEvent): Unit = publish.runWith(Source.single(elem))

  /**
    * Publish many elements to this cache.
    */
  def publishMany(it: immutable.Iterable[CacheEvent]): Unit = publish.runWith(Source(it))

  /**
    * A source used to subscribe to [[APIMessage]]s sent to this cache.
    */
  def subscribeAPI: Source[APIMessage, NotUsed] = subscribe.via(CacheStreams.createApiMessages)

  /**
    * Subscribe an actor to this cache using [[https://doc.akka.io/api/akka/current/akka/stream/scaladsl/Sink$.html#actorRef[T](ref:akka.actor.ActorRef,onCompleteMessage:Any):akka.stream.scaladsl.Sink[T,akka.NotUsed] Sink.actorRef]].
    */
  def subscribeAPIActor(actor: classic.ActorRef, completeMessage: Any, onFailureMessage: Throwable => Any)(
      specificEvent: Class[_ <: APIMessage]*
  ): Unit =
    subscribeAPI
      .filter(msg => specificEvent.exists(_.isInstance(msg)))
      .runWith(Sink.actorRef(actor, completeMessage, onFailureMessage))

  /**
    * Subscribe an actor to this cache using [[https://doc.akka.io/api/akka/current/akka/stream/scaladsl/Sink$.html#actorRefWithAck[T](ref:akka.actor.ActorRef,onInitMessage:Any,ackMessage:Any,onCompleteMessage:Any,onFailureMessage:Throwable=%3EAny):akka.stream.scaladsl.Sink[T,akka.NotUsed] Sink.actorRefWithAck]].
    */
  def subscribeAPIActorWithAck(
      actor: classic.ActorRef,
      initMessage: Any,
      ackMessage: Any,
      completeMessage: Any,
      failureMessage: Throwable => Any = classic.Status.Failure
  )(specificEvent: Class[_ <: APIMessage]*): Unit =
    subscribeAPI
      .filter(msg => specificEvent.exists(_.isInstance(msg)))
      .runWith(Sink.actorRefWithBackpressure(actor, initMessage, ackMessage, completeMessage, failureMessage))
}
object Cache {

  /**
    * Creates a cache for a bot. This should be shared for the whole bot.
    */
  def create(
      cacheProcessor: MemoryCacheSnapshot.CacheProcessor = MemoryCacheSnapshot.defaultCacheProcessor
  )(implicit system: ActorSystem[Nothing]): Cache = {
    val (publish, subscribe)               = CacheStreams.cacheStreams(cacheProcessor)
    val (gatewayPublish, gatewaySubscribe) = CacheStreams.gatewayEvents[Any]

    //Keep it drained if nothing else is using it
    subscribe.runWith(Sink.ignore)

    Cache(publish, subscribe, gatewayPublish, gatewaySubscribe)
  }
}
