/*
 * Copyright 2020 Google LLC
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

package models

import java.net.URL

import models.Direction.Direction
import play.api.libs.functional.syntax._
import play.api.libs.json.{JsString, Writes, __}

import scala.concurrent.duration.FiniteDuration

object Events {

  case object PlayersRefresh
  case object ScoresReset

  case class ArenaDimsAndPlayers(name: Arena.Name, emojiCode: Arena.EmojiCode, dims: Arena.Dimensions, playerStates: Map[Player, PlayerState], canResetIn: FiniteDuration)
  case class ArenaUpdate(path: Arena.Path, arenaDimsAndPlayers: ArenaDimsAndPlayers)

  implicit val urlWrites = Writes[URL](url => JsString(url.toString))

  implicit val playerPlayerStateWrites: Writes[(Player, PlayerState)] = (
    (__ \ "name").write[String] ~
    (__ \ "pic").write[URL] ~
    (__ \ "x").write[Int] ~
    (__ \ "y").write[Int] ~
    (__ \ "direction").write[Direction] ~
    (__ \ "wasHit").write[Boolean] ~
    (__ \ "score").write[Int] ~
    (__ \ "responseTimeMS").writeNullable[Long]
  ) { playerPlayerState: (Player, PlayerState) =>
    val (player, playerState) = playerPlayerState

    (player.name, player.pic, playerState.x, playerState.y, playerState.direction, playerState.wasHit, playerState.score, playerState.responseTime.map(_.toMillis))
  }

  implicit val arenaDimsAndPlayersWrites = (
    (__ \ "name").write[String] ~
    (__ \ "emoji_code").write[String] ~
    (__ \ "width").write[Int] ~
    (__ \ "height").write[Int] ~
    (__ \ "can_reset_in_seconds").write[Long] ~
    (__ \ "players").write[Map[String, (Player, PlayerState)]]
  ) { arenaDimsAndPlayersWrites: ArenaDimsAndPlayers =>
    val playerPlayerStates = arenaDimsAndPlayersWrites.playerStates.map { case (player, playerState) =>
      (player.service, (player, playerState))
    }

    (
      arenaDimsAndPlayersWrites.name,
      arenaDimsAndPlayersWrites.emojiCode,
      arenaDimsAndPlayersWrites.dims.width,
      arenaDimsAndPlayersWrites.dims.height,
      arenaDimsAndPlayersWrites.canResetIn.toSeconds,
      playerPlayerStates,
    )
  }

}
