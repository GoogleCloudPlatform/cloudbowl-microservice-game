/*
 * Copyright 2019 Google LLC
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

package cloudpit

import com.github.mauricio.async.db.pool.{PartitionedConnectionPool, PoolConfiguration}
import com.github.mauricio.async.db.postgresql.pool.PostgreSQLConnectionFactory
import com.github.mauricio.async.db.postgresql.util.URLParser
import io.getquill.{PostgresAsyncContext, SnakeCase}
import javax.inject.{Inject, Singleton}
import play.api.Configuration
import play.api.inject.ApplicationLifecycle

import scala.concurrent.ExecutionContext


@Singleton
class DB @Inject()(lifecycle: ApplicationLifecycle, playConfig: Configuration)(implicit ec: ExecutionContext) {

  private val maybeDbUrl = playConfig.getOptional[String]("db.default.url")
  private val maybeDbUsername = playConfig.getOptional[String]("db.default.username")
  private val maybeDbPassword = playConfig.getOptional[String]("db.default.password")

  private val maybeConfig = for {
    config <- maybeDbUrl.map(URLParser.parse(_))
    username <- maybeDbUsername
  } yield config.copy(username = username, password = maybeDbPassword)

  private val config = maybeConfig.getOrElse(throw new Exception("database config not set"))

  private val connectionFactory = new PostgreSQLConnectionFactory(config)

  private val defaultPoolConfig = PoolConfiguration.Default

  private val maxObjects = playConfig.getOptional[Int]("db.default.max-objects").getOrElse(defaultPoolConfig.maxObjects)
  private val maxIdleMillis = playConfig.getOptional[Long]("db.default.max-idle-millis").getOrElse(defaultPoolConfig.maxIdle)
  private val maxQueueSize = playConfig.getOptional[Int]("db.default.max-queue-size").getOrElse(defaultPoolConfig.maxQueueSize)
  private val validationInterval = playConfig.getOptional[Long]("db.default.max-queue-size").getOrElse(defaultPoolConfig.validationInterval)

  private val poolConfig = new PoolConfiguration(maxObjects, maxIdleMillis, maxQueueSize, validationInterval)

  private val numberOfPartitions = playConfig.getOptional[Int]("db.default.number-of-partitions").getOrElse(4)

  private val pool = new PartitionedConnectionPool(
    connectionFactory,
    poolConfig,
    numberOfPartitions,
    ec
  )

  lifecycle.addStopHook { () =>
    pool.close
  }

  val ctx = new PostgresAsyncContext(SnakeCase, pool)
}

@Singleton
class DAO @Inject()(db: DB)(implicit ec: ExecutionContext) {



  if (false) {
    ec
  }

  /*
  case class ExpectedOne(got: Int) extends Exception {
    override def getMessage: String = s"Expected one result, got $got"
  }

  private def expectOne[A](result: RunQueryResult[A]): Future[A] = {
    result match {
      case value :: Nil => Future.successful(value)
      case _            => Future.failed(ExpectedOne(result.size))
    }
  }

  private def getPlayer(service: Player.Service): Future[Player] = {
    run {
      quote {
        query[Player].filter(_.service == lift(service))
      }
    }.flatMap(expectOne)
  }

  def createPlayerIfNotExists(player: Player): Future[Unit] = {
    getPlayer(player.service).recoverWith {
      case _: ExpectedOne =>
        run {
          quote {
            query[Player].insert(player)
          }
        }
    }.map(_ => Unit)
  }

  def getArena(arenaPath: Arena.Path): Future[Arena] = {
    run {
      quote {
        query[Arena].filter(_.path == lift(arenaPath))
      }
    }.flatMap(expectOne)
  }

  def arenaPlayers(arena: Arena): Future[Seq[ArenaPlayer]] = {
    run {
      quote {
        query[ArenaPlayer].filter(_.arenaPath == lift(arena.path))
      }
    }
  }

  def addPlayerToArena(player: Player, arenaPath: Arena.Path): Future[Unit] = {
    for {
      _ <- createPlayerIfNotExists(player)
      arena <- getArena(arenaPath)
      players <- arenaPlayers(arena)


    } yield ()
  }

   */
}
