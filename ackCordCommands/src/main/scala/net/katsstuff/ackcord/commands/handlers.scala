/*
 * This file is part of AckCord, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2017 Katrix
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
package net.katsstuff.ackcord.commands

import akka.actor.{Actor, ActorRef, Status}
import net.katsstuff.ackcord.DiscordClient.ClientActor
import net.katsstuff.ackcord.commands.CommandDispatcher.Command
import net.katsstuff.ackcord.commands.CommandParser.{ParseError, ParsedCommand}
import net.katsstuff.ackcord.data.{CacheSnapshot, Message}
import net.katsstuff.ackcord.syntax._
import net.katsstuff.ackcord.util.RequestFailedResponder
import shapeless.{TypeCase, Typeable}

/**
  * Base trait common to all command actors.
  */
trait BaseCommandActor extends Actor {

  /**
    * Create a new [[RequestFailedResponder]]. Useful for detecting errors.
    */
  def errorResponder: ActorRef = context.actorOf(RequestFailedResponder.props(self))

  /**
    * Handle a potential error comming from for example using [[errorResponder]].
    * @param e The exception
    */
  def handleFailure(e: Throwable): Unit = throw e
}

/**
  * An actor that handles a command and potential errors. Use for clarity, and implicit snapshot.
  */
trait CommandActor extends BaseCommandActor {
  override def receive: Receive = {
    case Command(msg, args, c) => handleCommand(msg, args)(c)
    case Status.Failure(e)     => handleFailure(e)
  }

  /**
    * Handle a command sent to this actor.
    * @param msg The message that triggered this
    * @param args The already parsed args. These will not include stuff like
    *             the category, mention and command name.
    * @param c The cache snapshot
    */
  def handleCommand(msg: Message, args: List[String])(implicit c: CacheSnapshot): Unit
}

/**
  * An actor that handles a parsed command and potential errors. Use for clarity,
  * error handling, and implicit snapshot.
  * @param typeable A typeable of the expected arg type. Used to make sure
  *                 that a the correct type is received.
  * @tparam A The arg type
  */
abstract class ParsedCommandActor[A](implicit typeable: Typeable[A]) extends BaseCommandActor {

  def client: ClientActor

  val IsA: TypeCase[A] = TypeCase[A]

  override def receive: Receive = {
    case ParsedCommand(msg, IsA(args), remaining, c) => handleCommand(msg, args, remaining)(c)
    case ParseError(msg, e, c) => handleParseError(msg, e)(c)
  }

  /**
    * Handle a parsed command sent to this actor
    * @param msg The base message
    * @param args The parsed arguments
    * @param remaining The remaining arguments
    * @param c The current cache
    */
  def handleCommand(msg: Message, args: A, remaining: List[String])(implicit c: CacheSnapshot): Unit

  /**
    * Handle a parse error during a command. Default behavior is to send the error back to the user.
    * @param msg The message
    * @param e The error message from the parser
    * @param c The current cache
    */
  def handleParseError(msg: Message, e: String)(implicit c: CacheSnapshot): Unit =
    msg.tChannel.foreach(client ! _.sendMessage(e))
}
