import java.io.File
import java.nio.file.Paths

import akka.pattern.ask
import akka.util.Timeout
import mortar.server.Server
import mortar.spec.{ConfigRequest, PubkeyRequest}
import mortar.util.Util
import org.scalatest.FlatSpec

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.util.{Failure, Success}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.HttpRequest

import scala.io.Source

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.stream.ActorMaterializer

import scala.concurrent.Future




trait TestInvariants {
  val config = Util.MortarConfig(Paths.get("application.conf"))
  implicit val timeout = Timeout(config.app.timeout.seconds)
  implicit val system = ActorSystem()
  implicit val materializer = ActorMaterializer()
}

class ServerTest extends FlatSpec with TestInvariants {
  val srv = new Server(config)
  srv.start()

  "The server" should "have a matching configuration" in {
    val s = srv.configActor ? ConfigRequest

    s onComplete {
      case Success(srv) =>
        assert(srv == config)
      case Failure(e) =>
        fail(e)
    }
  }
  it should "have a pubkey" in {
    val k = (srv.pubkeyActor ? PubkeyRequest).mapTo[String]
    val my_key = Source
      .fromFile(new File(config.local.sec.ssh.pub))
      .mkString
      .trim
    k onComplete {
      case Success(key) =>
        assert(key == my_key)
      case Failure(e) =>
        fail(e)
    }
  }
  it should "serve the pubkey route at /pubkey" in {
    val k = (srv.pubkeyActor ? PubkeyRequest).mapTo[String]
    val req = Http().singleRequest(
      HttpRequest(uri = s"http://localhost:${config.app.port}/pubkey"))
    req onComplete {
      case Success(pubkey) =>
        k onComplete {
          case Success(cmp_key) =>
            assert(pubkey.entity.getDataBytes().toString() == cmp_key)
          case Failure(e) =>
            fail(e)
        }
      case Failure(e) =>
        fail(e)
    }
  }
}
