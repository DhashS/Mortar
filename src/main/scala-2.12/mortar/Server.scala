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
import com.cedarsoftware.util.io.{JsonParser, JsonWriter}

import scala.collection.JavaConverters._
import scala.concurrent.{Await, ExecutionContext, Future, Promise}
import scala.util.{Failure, Success, Try}
import scala.sys.process.{Process, ProcessLogger}
import scala.concurrent.duration._
import akka.pattern.ask
import akka.routing.ConsistentHashingPool

case class Evt(data: RDiffRequest)

object Server {
  // needed to run the route
  implicit val system = ActorSystem("mortar-http-server-actorsystem")
  implicit val materializer = ActorMaterializer()
  // needed for the future map/flatmap in the end
  implicit val executionContext: ExecutionContext = system.dispatcher
  implicit val timeout = Timeout(60.seconds)
  private val waitingActor =
    system.actorOf(Props[WaitingActor], "waiting-actor")
  private val loggingActor = system.actorOf(Props[LogActor], "logging-actor")
  private val spaceActor =
    system.actorOf(Props[FreeSpaceActor], "free-space-actor")
  private val routerActor =
    system.actorOf(Props[RDiffRouterActor], "router-actor")

}
class Server(config: ApplicationConfig)
    extends Directives
    with MortarJsonSupport {

  import Server._
  private val configActor =
    system.actorOf(Props(new ConfigActor(config)), "config-actor")
  Logger.info("Actors initialized")

  def routes(): Route = {
    val route =
      path("connect") {
        post {
          entity(as[MortarRequest]) { req =>
            config.remote.find(x => x.pubkey == req.key) match {
              case Some(client_config) => {
                val isSpace = Await
                  .result((spaceActor ? SpaceRequest(client_config, req))
                            .mapTo[Boolean],
                          60.seconds)
                if (isSpace) {
                  //TODO per-remote storage quota
                  //TODO duplicity intent
                  //TODO rsync intent
                  client_config.security match {
                    case "container" =>
                      complete(client_config.toString) //TODO remove magic string
                    case "sync" =>
                      waitingActor ! RDiffRequest(client_config, req) //Add to Actor system queue
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

case class WaitingJobsState(commands: List[RDiffRequest] = Nil) {
  def updated(command: Evt): WaitingJobsState = copy(command.data :: commands)
  def size: Int = commands.length
  override def toString: String = commands.toString
}
class WaitingActor extends PersistentActor {
  override def persistenceId = "waiting-actor"
  private var state = WaitingJobsState()
  private val RDiffRouterActor = context.actorSelection("/user/router-actor")

  def updateState(command: Evt): Unit =
    state = state.updated(command)

  val receiveRecover: Receive = {
    case evt: Evt => updateState(evt)
    case SnapshotOffer(_, snapshot: WaitingJobsState) => state = snapshot
  }
  val receiveCommand: Receive = {
    case data: RDiffRequest => {
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
        RDiffRouterActor ! data
      }
    }
    case MachineRequest => {
      sender ! state.commands
    }
  }
}

class ConfigActor(config: ApplicationConfig) extends Actor {
  override def receive: Receive = {
    case ConfigRequest => {
      sender ! config
    }
  }
}

class RDiffRouterActor extends Actor {
  import Server.timeout
  private val configActor = context.actorSelection("/user/config-actor")
  private val config = Await.result(
    (configActor ? ConfigRequest).mapTo[ApplicationConfig],
    60.seconds)
  private val router = context.actorOf(
    ConsistentHashingPool(config.remote.length).props(Props[RDiffActor]),
    "routing-actor")
  override def receive: Receive = {
    case rdr: RDiffRequest => {
      router ! rdr
    }
  }
}

class RDiffActor extends Actor {
  import Server.timeout
  private val configActor = context.actorSelection("/user/config-actor")
  private val config = Await.result(
    (configActor ? ConfigRequest).mapTo[ApplicationConfig],
    60.seconds)

  override def receive: Receive = {
    //rdr and rdp pertain to the actual rdiff-backup
    case rdr: RDiffRequest => {
      val mortarLog = context.actorSelection("/user/logging-actor")
      val mortarWaitingActor = context.actorSelection("/user/waiting-actor")
      val logger = ProcessLogger(line => mortarLog ! StdOutLogLine(line),
                                 line => mortarLog ! StdErrLogLine(line))
      val rdp = Process(
        s"rdiff-backup -b ${rdr.machine.uname}@${rdr.machine.hostname}::${rdr.req.path} "
          + s"${config.local.recvPath}")

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
      Logger.error(
        JsonWriter.objectToJson(
          msg,
          Map(JsonWriter.PRETTY_PRINT -> true).asJava
            .asInstanceOf[java.util.Map[String, Object]]))
    }
    case msg: StdOutLogLine => {
      Logger.trace(
        JsonWriter.objectToJson(
          msg,
          Map(JsonWriter.PRETTY_PRINT -> true).asJava
            .asInstanceOf[java.util.Map[String, Object]]))
    }
  }
}

class FreeSpaceActor extends Actor {
  import Server.timeout
  import Server.executionContext

  private val configActor = context.actorSelection("/user/config-actor")
  private val config = Await.result(
    (configActor ? ConfigRequest).mapTo[ApplicationConfig],
    60.seconds)

  override def receive: Receive = {
    case req: SpaceRequest => {
      val mortarWaitingActor = context.actorSelection("/user/waiting-actor")
      var totalSpaceInTransit = Bytes(0)

      val inProgress = Await.result(
        (mortarWaitingActor ? MachineRequest).mapTo[List[RDiffRequest]],
        60.seconds)
      if (inProgress.nonEmpty) {
        totalSpaceInTransit = inProgress
          .map(x => x.req.space)
          .sum
      }

      val spaceLeftOnDevice = Bytes(
        new File(config.local.recvPath).getTotalSpace)
      if (spaceLeftOnDevice - totalSpaceInTransit - req.req.space > Bytes(0)) {
        sender ! true
      } else {
        sender ! false
      }
    }
  }
}
