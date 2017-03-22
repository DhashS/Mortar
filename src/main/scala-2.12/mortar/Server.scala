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

import akka.http.javadsl.server.PathMatchers

import scala.io.Source
import squants.information.Bytes
import squants.information.Information
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives
import akka.http.scaladsl.server.Directives._
import akka.pattern.ask
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import spray.json._
import mortar.spec._
import org.pmw.tinylog.Logger

class Server(config: ApplicationConfig)
    extends Directives
    with MortarJsonSupport {
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
          val freeSpace: Future[Information] = Future { getFreeSpace }
          onSuccess(freeSpace) { space =>
            complete(FreeSpace(space))
          }
        }
        //what is the server's pubkey
      } ~ path("pubkey") {
        get {
          val key: Future[Option[String]] = Future {
            try {
              val pkeyfile = new File(config.local.sec.ssh.pub) //grab my pubkey
              Some(Source.fromFile(pkeyfile).mkString)
            } catch {
              case e: Throwable =>
                Logger.error("Unable to read pubkey")
                Logger.error(e)
                None
            }
          }
          onSuccess(key) { k =>
            k match {
              case Some(pkey) => complete(PubKey(pkey))
              case None => complete("No Pubkey")
            }
          }
        }
      } ~ path("allocate") {
        post {
          entity(as[SpaceRequest]) { req =>
            val self_space: Future[Information] = Future { getFreeSpace }
            onSuccess(self_space) { self =>
              {
                config.remote.find(x => x.pubkey == req.pub) match { //find the host that has the same pubkey
                  case Some(remote) =>
                    if (self - req.space > Bytes(0)) {
                      complete(remote.hostname)
                      //TODO check quota of that pubkey
                      //TODO Allocate space
                      //TODO send POST to requestor
                      //TODO rollback
                    } else {
                      complete(StatusCodes.InsufficientStorage)
                    }
                  case None =>
                    Logger.trace(
                      s"Client with pubkey ${req.pub} tried to aquire ${req.space}, denied as pubkey didn't match")
                    complete(StatusCodes.Forbidden)
                }
              }
            }
          }
        }
      } ~ path("allocate" / "done") {
        post {
          entity(as[AllocateResponse]) { req =>
            if (req.status) {
              complete("ast")
              //TODO respond with intent
              //TODO begin the rsync
              //TODO send the response of a completed transfer and bytecount
            } else {
              //TODO fail
              complete("lol")
            }
          }
        }
      } ~ path("sync") {
        //TODO register sync state start
        complete("TODO")
      } ~ path("sync" / "done") {
        //TODO register sync state complete
        complete("TODO")
      }
    route
  }
  def getFreeSpace: Information = {
    Information(config.local.maxSpace).get - Bytes(
      new File(config.local.recvPath).getTotalSpace)
  }
  def start() {
    val bind = Http()
      .bindAndHandle(routes(), "localhost", config.app.port) //TODO Https
    Logger.info(s"Server started on port ${config.app.port}")
    StdIn.readLine() //TODO replace
    bind
      .flatMap(_.unbind())
      .onComplete(_ => system.terminate())
  }
}
