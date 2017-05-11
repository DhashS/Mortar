package mortar.app.MortarServer

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives._
import akka.stream.ActorMaterializer
import squants.information.Information

import scala.io.StdIn
import scala.concurrent.Future
import java.io.File

import scala.concurrent.forkjoin.ThreadLocalRandom
import akka.actor.Actor
import akka.actor.ActorLogging
import akka.cluster.Cluster
import akka.cluster.ddata.DistributedData
import akka.cluster.ddata.ORSet
import akka.cluster.ddata.ORSetKey
import akka.cluster.ddata.Replicator
import akka.cluster.ddata.Replicator._
import akka.http.javadsl.server.PathMatchers

import akka.actor._
import akka.persistence._

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
import com.cedarsoftware.util.io.{JsonWriter, JsonParser}

import scala.sys.process.{ProcessLogger, Process}

class Server(config: ApplicationConfig)
    extends Directives
    with MortarJsonSupport {
  // needed to run the route
  private implicit val system = ActorSystem("mortar-http-server-actorsystem")
  private implicit val materializer = ActorMaterializer()
  // needed for the future map/flatmap in the end
  private implicit val executionContext = system.dispatcher
  val waitingActor = system.actorOf(Props[WaitingActor], "waiting-actor")
  val loggingActor = system.actorOf(Props[LogActor], "logging-actor")
  Logger.info("Actors initialized")
  //TODO InProgressActor
  //TODO DuplicityJob
  //TODO RsyncJob
  //TODO DoneActor

  def routes(): Route = {
    val route =
      path("connect") {
        post {
          entity(as[MortarRequest]) { req =>
            config.remote.find(x => x.pubkey == req.key) match {
              case Some(client_config) => {
                if (getFreeSpace > Bytes(0)) {
                  //TODO per-remote storage quota
                  //TODO duplicity intent
                  //TODO rsync intent
                  client_config.security match {
                    case "container" =>
                      complete(client_config.toString) //TODO remove magic string
                    case "sync" =>
                      waitingActor ! RDiffRequest(client_config, req, config) //Add to Actor system queue
                      complete(StatusCodes.Accepted)
                    case _ =>
                      complete(client_config.toString) //echo since this should never happen
                    //TODO make this never happen
                  }
                } else {
                  complete(StatusCodes.InsufficientStorage)
                }
              }
              case None => complete(StatusCodes.Forbidden)
            }
          }
        }
      }
    route
  }
  def getFreeSpace: Information = {
    //TODO: add an in-transit flag
    //So I can resolve the race condition between multiple data dumps
    Information(config.local.maxSpace).get - Bytes(
      new File(config.local.recvPath).getTotalSpace)
  }
  def start(): Unit = {
    val bind = Http()
      .bindAndHandle(routes(), "localhost", config.app.port) //TODO Https
    Logger.info(s"Server started on port ${config.app.port}")
    val line = StdIn.readLine() //TODO replace
    bind
      .flatMap(_.unbind())
      .onComplete(_ => system.terminate())
    Logger.info("Server shutdown!")
  }
  def addToWaiting(conf: RemoteMachine): Unit = {}
}

//TODO state resolution FSM
case class RDiffRequest(machine: RemoteMachine,
                        req: MortarRequest,
                        config: ApplicationConfig)
case class RDiffDone(machine: RemoteMachine)
case class StdOutLogLine(line: String)
case class StdErrLogLine(line: String)
case class Cmd(data: RemoteMachine)
case class Evt(data: RDiffRequest)

case class QueueState(commands: List[RDiffRequest] = Nil) {
  def updated(command: Evt): QueueState = copy(command.data :: commands)
  def size: Int = commands.length
  override def toString: String = commands.toString
}
class WaitingActor extends PersistentActor {
  override def persistenceId = "waiting-actor-1"
  var state = QueueState()

  def updateState(command: Evt): Unit =
    state = state.updated(command)

  val receiveRecover: Receive = {
    case evt: Evt => updateState(evt)
    case SnapshotOffer(_, snapshot: QueueState) => state = snapshot
  }
  val receiveCommand: Receive = {
    case data: RDiffRequest =>
      state.commands.find(_ == data.machine) match {
        case Some(machine) =>
        case None =>
      }
      persist(Evt(data)) { event =>
        Logger.trace(s"Persisting message ${JsonWriter.objectToJson(data)}")
        updateState(event)
        saveSnapshot(state)
        context.system.eventStream.publish(event)
        Logger.trace(s"Message persisted!")
      }
  }
}
class RDiffActor extends Actor {
  override def receive: Receive = {
    //rdr and rdp pertain to the actual rdiff-backup
    case rdr: RDiffRequest => {
      val mortarLog = context.actorSelection("logging-actor")
      val logger = ProcessLogger(line => mortarLog ! StdOutLogLine(line),
                                 line => mortarLog ! StdErrLogLine(line))
      val rdp = Process(
        s"rdiff-backup ${rdr.machine.uname}@${rdr.machine.hostname}::${rdr.req.path} " + s"${rdr.config.local.recvPath}")

    }

  }
}
class LogActor extends Actor {
  override def receive: Receive = {
    case msg: RDiffDone => {}
  }
}
