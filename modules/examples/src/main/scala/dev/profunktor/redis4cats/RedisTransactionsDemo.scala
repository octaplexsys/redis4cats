/*
 * Copyright 2018-2020 ProfunKtor
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.profunktor.redis4cats

import cats.effect._
import cats.implicits._
import dev.profunktor.redis4cats.algebra.RedisCommands
import dev.profunktor.redis4cats.connection._
import dev.profunktor.redis4cats.effect.Log
import dev.profunktor.redis4cats.interpreter.Redis
import dev.profunktor.redis4cats.transactions._
import java.util.concurrent.TimeoutException

object RedisTransactionsDemo extends LoggerIOApp {

  import Demo._

  def program(implicit log: Log[IO]): IO[Unit] = {
    val key1 = "test1"
    val key2 = "test2"

    val showResult: String => Option[String] => IO[Unit] = key =>
      _.fold(putStrLn(s"Not found key: $key"))(s => putStrLn(s"$key: $s"))

    val commandsApi: Resource[IO, RedisCommands[IO, String, String]] =
      for {
        uri <- Resource.liftF(RedisURI.make[IO](redisURI))
        client <- RedisClient[IO](uri)
        redis <- Redis[IO, String, String](client, stringCodec)
      } yield redis

    commandsApi
      .use { cmd =>
        val tx = RedisTransaction(cmd)

        val getters =
          cmd.get(key1).flatTap(showResult(key1)) *>
              cmd.get(key2).flatTap(showResult(key2))

        val tx1 =
          tx.run(
              cmd.set(key1, "sad"),
              cmd.set(key2, "windows"),
              cmd.get(key1),
              cmd.set(key1, "nix"),
              cmd.set(key2, "linux"),
              cmd.get(key1)
            )
            .handleErrorWith {
              case TransactionAborted =>
                putStrLn("[Error] - Transaction Aborted").as(List.empty)
              case _: TimeoutException =>
                putStrLn("[Error] - Timeout").as(List.empty)
            }

        getters >> tx1.flatMap {
          case (() :: () :: Some("sad") :: () :: () :: Some("nix") :: Nil) =>
            putStrLn(">>> Got expected result")
          case xs =>
            putStrLn(">>> Unexpected result") >> xs.traverse_(putStrLn)
        } >> getters.void >> putStrLn("Some more computations after tx...")
      }
  }

}
