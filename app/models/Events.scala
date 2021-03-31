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

import models.Arena.ArenaState

import java.net.URL
import models.Direction.Direction
import play.api.libs.functional.syntax._
import play.api.libs.json.{JsString, Writes, __}

import scala.concurrent.duration.FiniteDuration

object Events {

  sealed trait PlayerUpdate
  case class PlayerJoin(player: Player) extends PlayerUpdate
  case class PlayerLeave(service: Player.Service) extends PlayerUpdate

  case object ScoresReset

  case class ArenaUpdate(arenaState: ArenaState, canResetIn: FiniteDuration)

  implicit val urlWrites: Writes[URL] = Writes[URL](url => JsString(url.toString))

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

  implicit val arenaUpdateWrites: Writes[ArenaUpdate] = (
    (__ \ "name").write[String] ~
    (__ \ "emoji_code").write[String] ~
    (__ \ "width").write[Int] ~
    (__ \ "height").write[Int] ~
    (__ \ "can_reset_in_seconds").write[Long] ~
    (__ \ "players").write[Map[Player, PlayerState]]
  ) { arenaUpdate: ArenaUpdate =>
    (
      arenaUpdate.arenaState.config.name,
      arenaUpdate.arenaState.config.emojiCode,
      arenaUpdate.arenaState.dims.width,
      arenaUpdate.arenaState.dims.height,
      arenaUpdate.canResetIn.toSeconds,
      arenaUpdate.arenaState.state,
    )
  }


}
