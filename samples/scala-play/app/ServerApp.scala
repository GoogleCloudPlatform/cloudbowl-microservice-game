import play.api.Mode
import play.api.mvc.Results
import play.api.routing.Router
import play.api.routing.sird._
import play.api.libs.json._
import play.core.server.{DefaultAkkaHttpServerComponents, ServerConfig}

import scala.util.{Random, Try}

object ServerApp extends App {

  case class Self(href: String)
  implicit val selfReads = Json.reads[Self]

  case class Links(self: Self)
  implicit val linksReads = Json.reads[Links]

  case class PlayerState(x: Int, y: Int, direction: String, wasHit: Boolean, score: Int)
  implicit val playerStateReads = Json.reads[PlayerState]

  case class Arena(dims: List[Int], val state: Map[String, PlayerState])
  implicit val arenaReads = Json.reads[Arena]

  case class ArenaUpdate(_links: Links, arena: Arena)
  implicit val arenaUpdateReads = Json.reads[ArenaUpdate]

  val components = new DefaultAkkaHttpServerComponents {
    private[this] lazy val port = sys.env.get("PORT").flatMap(s => Try(s.toInt).toOption).getOrElse(8080)
    private[this] lazy val mode = if (configuration.get[String]("play.http.secret.key").contains("changeme")) Mode.Dev else Mode.Prod

    override lazy val serverConfig: ServerConfig = ServerConfig(port = Some(port), mode = mode)

    override lazy val router: Router = Router.from {
      case POST(p"/$_*") =>
        Action(parse.json[ArenaUpdate]) { request =>
          println(request.body)
          Results.Ok(Random.shuffle(Seq("F", "R", "L", "T")).head)
        }
    }
  }

  // server is lazy so eval it to start it
  components.server

}

