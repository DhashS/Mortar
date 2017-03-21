package mortar.server

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives._
import akka.stream.ActorMaterializer
import squants.information.Information

import scala.io.StdIn
import scala.concurrent.Future

import java.io.File
import scala.io.Source

import squants.information.Bytes
import akka.http.scaladsl.server.Directives
import akka.http.scaladsl.server.Route

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import spray.json._

import mortar.spec._

import org.pmw.tinylog.Logger

class Server(config: ApplicationConfig) extends Directives with MortarJsonSupport {
  // needed to run the route
  private implicit val system = ActorSystem("mortar-http-server-actorsystem")
  private implicit val materializer = ActorMaterializer()
  // needed for the future map/flatmap in the end
  private implicit val executionContext = system.dispatcher

  def routes(): Route = {
    val route =
      //How much free space does the server have
      path("free_space") {
        get {
          val freeSpace: Future[Information] = Future {
            Bytes(new File(".").getFreeSpace)
          }
          onSuccess(freeSpace) {
            space => complete(FreeSpace(space))
          }
        }
      //what is the server's pubkey
      } ~ path("pubkey") {
        get {
          val key: Future[Option[String]] = Future {
            try{
              val pkeyfile = new File(config.local.sec.ssh.pub)
              Some(Source.fromFile(pkeyfile).mkString)
            } catch {
              case e: Throwable =>
                Logger.error("Unable to read pubkey")
                Logger.error(e)
                None
            }
          }
          onSuccess(key) {
            k => k match {
              case Some(pkey) => complete(PubKey(pkey))
              case None => complete("No Pubkey")
            }
          }
        }
      } ~ path("allocate") {
        post {
          val fs = Http().singleRequest(HttpRequest(uri = s"localhost:${config.app.port}/free_space"))
          onSuccess(fs) {
            space => entity(as[FreeSpace]) {
              reqSpace: FreeSpace => reqSpace.-()
            }
          }
        }
      }
    route
  }

  def start() {
    val bind = Http()
      .bindAndHandle(routes(), "localhost", config.app.port)
    Logger.info(s"Server started on port ${config.app.port}")
    StdIn.readLine() //TODO replace
    bind
      .flatMap(_.unbind())
      .onComplete(_ => system.terminate())
  }
}
