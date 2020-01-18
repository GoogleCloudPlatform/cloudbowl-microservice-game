import play.api.Mode
import play.api.mvc.Results
import play.api.routing.Router
import play.api.routing.sird._
import play.core.server.{DefaultAkkaHttpServerComponents, ServerConfig}

import scala.util.{Random, Try}

object ServerApp extends App {

  val components = new DefaultAkkaHttpServerComponents {
    private[this] lazy val port = sys.env.get("PORT").flatMap(s => Try(s.toInt).toOption).getOrElse(9000)
    private[this] lazy val mode = if (configuration.get[String]("play.http.secret.key").contains("changeme")) Mode.Dev else Mode.Prod

    override lazy val serverConfig: ServerConfig = ServerConfig(port = Some(port), mode = mode)

    override lazy val router: Router = Router.from {
      case POST(p"/$_*") =>
        Action {
          Results.Ok(Random.shuffle(Seq("F", "R", "L", "T")).head)
        }
    }
  }

  // server is lazy so eval it to start it
  components.server

}

