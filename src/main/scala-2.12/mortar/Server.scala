package mortar.app.MortarServer

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives._
import akka.stream.ActorMaterializer
import squants.information.Information

import scala.io.StdIn
import java.io.{File, IOException}
import akka.actor.Actor
import akka.actor.ActorLogging
import akka.cluster.Cluster
import akka.cluster.ddata.DistributedData
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
import com.cedarsoftware.util.io.{JsonParser, JsonWriter}

import scala.collection.JavaConverters._
import scala.sys.process.{Process, ProcessLogger}

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
}

//TODO state resolution FSM
case class RDiffRequest(machine: RemoteMachine,
                        req: MortarRequest,
                        config: ApplicationConfig)
case class RDiffDone(req: RDiffRequest)
case class RDiffFailure(machine: RemoteMachine, e: Exception)
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
  override def persistenceId = "waiting-actor"
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
        Logger.trace(s"Persisting message ${JsonWriter
          .objectToJson(data,
                        Map(JsonWriter.PRETTY_PRINT -> true).asJava
                          .asInstanceOf[java.util.Map[String, Object]])}")
        updateState(event)
        saveSnapshot(state)
        context.system.eventStream.publish(event)
        Logger.trace(s"Message persisted!")
        val job =
          context.actorOf(Props[RDiffActor], s"rdiff-${data.machine.hostname}")
        job ! data
      }
  }
}

class RDiffActor extends Actor {
  override def receive: Receive = {
    //rdr and rdp pertain to the actual rdiff-backup
    case rdr: RDiffRequest => {
      val mortarLog = context.actorSelection("/user/logging-actor")
      val mortarWaitingActor = context.actorSelection("/user/waiting-actor")
      val logger = ProcessLogger(line => mortarLog ! StdOutLogLine(line),
                                 line => mortarLog ! StdErrLogLine(line))
      val rdp = Process(
        s"rdiff-backup -b ${rdr.machine.uname}@${rdr.machine.hostname}::${rdr.req.path} "
          + s"${rdr.config.local.recvPath}")

      try {
        val proc = rdp.run(logger)
        val exit = proc.exitValue()
        if (exit != 0) {
          mortarLog ! RDiffFailure(
            rdr.machine,
            new IOException(s"rdiff-backup request for " +
              s"${rdr.machine.hostname}::${rdr.req.path} exited with code ${exit}"))
        } else {
          mortarWaitingActor ! RDiffDone(rdr)
          mortarLog ! RDiffDone(rdr)
        }
      } catch {
        case e: Exception => {
          mortarLog ! RDiffFailure(rdr.machine, e)
          mortarWaitingActor ! RDiffFailure(rdr.machine, e)
        }
      } finally {
        context stop self
      }
    }

  }
}
class LogActor extends Actor {
  override def receive: Receive = {
    case msg: RDiffFailure => {}
    case msg: RDiffDone => {println(s"Rdiff successful for ${msg.req.machine.hostname}")}
    case msg: StdErrLogLine => {
      Logger.error(JsonWriter.objectToJson(msg,
        Map(JsonWriter.PRETTY_PRINT -> true)
          .asInstanceOf[java.util.Map[String, Object]]))
    }
    case msg: StdOutLogLine => {
      Logger.trace(JsonWriter.objectToJson(msg,
        Map(JsonWriter.PRETTY_PRINT -> true)
          .asInstanceOf[java.util.Map[String, Object]]))
    }
  }
}
