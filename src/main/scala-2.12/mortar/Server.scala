package mortar.app.MortarServer

import akka.actor.ActorSystem
import akka.http.scaladsl.Http

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

import squants.information.Bytes
import squants.information.Information
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives
import akka.util.Timeout
import mortar.spec._
import org.pmw.tinylog.Logger
import com.cedarsoftware.util.io.{JsonWriter, JsonParser}

import scala.collection.JavaConverters._
import scala.concurrent.{Await, Future, Promise}
import scala.util.{Try,Success,Failure}

import scala.sys.process.{Process, ProcessLogger}
import scala.concurrent.duration._
import akka.pattern.ask

case class Evt(data: RDiffRequest)


object Server {
  // needed to run the route
  implicit val system = ActorSystem("mortar-http-server-actorsystem")
  implicit val materializer = ActorMaterializer()
  // needed for the future map/flatmap in the end
  implicit val executionContext = system.dispatcher
  implicit val timeout = Timeout(60.seconds)
  val waitingActor = system.actorOf(Props[WaitingActor], "waiting-actor")
  val loggingActor = system.actorOf(Props[LogActor], "logging-actor")
  val spaceActor = system.actorOf(Props[FreeSpaceActor], "free-space-actor")
  Logger.info("Actors initialized")

}
class Server(config: ApplicationConfig) extends Directives with MortarJsonSupport {

  import Server._

  def routes(): Route = {
    val route =
      path("connect") {
        post {
          entity(as[MortarRequest]) { req =>
            config.remote.find(x => x.pubkey == req.key) match {
              case Some(client_config) => {
                val isSpace = Await.result(spaceActor ? SpaceRequest(client_config, config, req), 60.seconds)
                  .asInstanceOf[Boolean]
                if (isSpace) {
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
    case data: RDiffRequest => {
      state.commands.find(_ == data.machine) match {
        case Some(machine) =>
        case None =>
      }
      persist(Evt(data)) { event =>
        Logger.trace(s"Persisting message ${
          JsonWriter
            .objectToJson(data,
              Map(JsonWriter.PRETTY_PRINT -> true).asJava
                .asInstanceOf[java.util.Map[String, Object]])
        }")
        updateState(event)
        saveSnapshot(state)
        context.system.eventStream.publish(event)
        Logger.trace(s"Message persisted!")
        val job =
          context.actorOf(Props[RDiffActor], s"rdiff-${data.machine.hostname}")
        job ! data
      }
    }
    case MachineRequest => {
      sender ! state.commands
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
              s"${rdr.machine.hostname}::${rdr.req.path} exited with code $exit"))
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
    case msg: RDiffDone => {
      println(s"Rdiff successful for ${msg.req.machine.hostname}")
    }
    case msg: StdErrLogLine => {
      Logger.error(JsonWriter.objectToJson(msg,
        Map(JsonWriter.PRETTY_PRINT -> true)
          .asJava
          .asInstanceOf[java.util.Map[String, Object]]))
    }
    case msg: StdOutLogLine => {
      Logger.trace(JsonWriter.objectToJson(msg,
        Map(JsonWriter.PRETTY_PRINT -> true)
          .asJava
          .asInstanceOf[java.util.Map[String, Object]]))
    }
  }
}

class FreeSpaceActor extends Actor {
  import Server.timeout
  import Server.executionContext
  override def receive: Receive = {
    case req: SpaceRequest => {
      val mortarWaitingActor = context.actorSelection("/user/waiting-actor")
      val totalSpaceInTransit = Promise[Information]
      try {
        val inProgress = (mortarWaitingActor ? MachineRequest)
          .asInstanceOf[Future[List[RDiffRequest]]]
          .flatMap(x => Future{
            x.map(y => y.req.space).reduce((s1, s2) => s1 + s2)
          })
        totalSpaceInTransit success Await.result(inProgress, 60.seconds)
      } catch {
        case e: UnsupportedOperationException => { // this is when there are no pending jobs, the reduce fails
          totalSpaceInTransit success Bytes(0)
        }
      }
      val space = Await.result(totalSpaceInTransit.future, 60.seconds)
      val spaceLeftOnDevice = Bytes(new File(req.config.local.recvPath).getTotalSpace)
      if (spaceLeftOnDevice - space - req.req.space > Bytes(0)) {
        sender ! true
      } else {
        sender ! false
      }
    }
  }
}
