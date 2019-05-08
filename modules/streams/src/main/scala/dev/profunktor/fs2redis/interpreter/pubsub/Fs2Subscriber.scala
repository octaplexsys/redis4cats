/*
 * Copyright 2018-2019 ProfunKtor
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

package dev.profunktor.redis4cats.interpreter.pubsub

import cats.effect.{ ConcurrentEffect, ContextShift, Sync }
import cats.effect.concurrent.Ref
import cats.syntax.all._
import dev.profunktor.redis4cats.algebra.SubscribeCommands
import dev.profunktor.redis4cats.interpreter.pubsub.internals.{ Fs2PubSubInternals, PubSubState }
import dev.profunktor.redis4cats.domain.Fs2RedisChannel
import dev.profunktor.redis4cats.effect.{ JRFuture, Log }
import fs2.Stream
import fs2.concurrent.Topic
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection

class Fs2Subscriber[F[_]: ConcurrentEffect: ContextShift: Log, K, V](
    state: Ref[F, PubSubState[F, K, V]],
    subConnection: StatefulRedisPubSubConnection[K, V]
) extends SubscribeCommands[Stream[F, ?], K, V] {

  override def subscribe(channel: Fs2RedisChannel[K]): Stream[F, V] = {
    val getOrCreateTopicListener = Fs2PubSubInternals[F, K, V](state, subConnection)
    val setup: F[Topic[F, Option[V]]] =
      for {
        st <- state.get
        topic <- getOrCreateTopicListener(channel)(st)
        _ <- JRFuture(Sync[F].delay(subConnection.async().subscribe(channel.value)))
      } yield topic

    Stream.eval(setup).flatMap(_.subscribe(500).unNone)
  }

  override def unsubscribe(channel: Fs2RedisChannel[K]): Stream[F, Unit] =
    Stream.eval {
      JRFuture(Sync[F].delay(subConnection.async().unsubscribe(channel.value))).void
    }

}
