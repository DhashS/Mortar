package mortar

import java.io.{File, IOException}

import akka.actor.{Actor, ActorSystem, _}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.{Directives, Route}
import akka.pattern.ask
import akka.persistence._
import akka.routing.ConsistentHashingPool
import akka.stream.ActorMaterializer
import akka.util.Timeout
import com.cedarsoftware.util.io.JsonWriter
import mortar.spec._
import org.pmw.tinylog.Logger
import squants.information.Bytes

import scala.collection.JavaConverters._
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext}
import scala.io.StdIn
import scala.sys.process.{Process, ProcessLogger}

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

  /*
  This class is the route definition and request validation for the HTTP server.
   */

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
                val isSpace =
                  Await.result((spaceActor ? SpaceRequest(client_config, req))
                                 .mapTo[Boolean],
                               60.seconds)
                if (isSpace) {
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
  /*
  This class encapsulates the persistent state of the application
   */
  def updated(command: Evt): WaitingJobsState =
    copy(command.data :: commands) //TODO redo to actual structure (ordered list)
  def size: Int = commands.length
  override def toString: String = commands.toString
}
class WaitingActor extends PersistentActor {
  /*
  This actor manages the persistent state of the application.
  It keeps an ordered list of to-be-filled requests, so that if the application is restarted
  the requests are restarted as well
   */
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
    case req: RDiffDone => {
      /*
      A RDiff job has completed, this logs it and removes it from the persistent state
     */
    } //TODO state modification
    case MachineRequest => {
      /*
      This relays the persistent state to actors who ask, such as for space computation
       */
      sender ! state.commands
    }
  }
}

class ConfigActor(config: ApplicationConfig) extends Actor {
  /*
  This is a simple actor to distribute the application configuration object on demand to other actors
   */
  override def receive: Receive = {
    case ConfigRequest => {
      sender ! config
    }
  }
}

class RDiffRouterActor extends Actor {
  /*
  This actor is a router that spins a transfer actor per host and distributes jobs to them
   */
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
      /*
      Run when a new job is submitted. This sends it to the router, which ensures jobs for a given machine are executed
      in the requested order
       */
      router ! rdr
    }
  }
}

class RDiffActor extends Actor {
  /*
  This actor is spun up for each host, and manages transfer requests for that host
  It currently manages a single RDiff job, and its associated failure modes
   */

  import Server.timeout
  private val configActor = context.actorSelection("/user/config-actor")
  private val config = Await.result(
    (configActor ? ConfigRequest).mapTo[ApplicationConfig],
    60.seconds)

  override def receive: Receive = {
    //rdr and rdp pertain to the actual rdiff-backup
    case rdr: RDiffRequest => {
      /*
      This manages a remote rdiff job to local storage
       */
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
              s"${rdr.machine.hostname}::${rdr.req.path} exited with code $exit, check STDERR"))
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
  /*
  This actor gives me a centralized way to do logging.
  It currently encapsulates tinylog, but can be extended to use ActorLogging
   */
  override def receive: Receive = {
    case msg: RDiffFailure => {
      // A rdiff job has failed
      Logger.error(
        s"RDiff failed for ${msg.machine.hostname}\n" +
          s"reason: ${msg.e.getMessage}\n" +
          s"stacktrace: ${JsonWriter.objectToJson(
            msg.e.getStackTrace,
            Map(JsonWriter.PRETTY_PRINT -> true).asJava
              .asInstanceOf[java.util.Map[String, Object]])}")
    }
    case msg: RDiffDone => {
      // A rdiff job has completed successfully
      Logger.info(s"RDiff successful for ${msg.req.machine.hostname}")
    }
    case msg: StdErrLogLine => {
      // Something has emitted a STDERR line
      Logger.error(s"STDERR from ${JsonWriter.objectToJson(sender)}")
      Logger.error(
        JsonWriter.objectToJson(
          msg,
          Map(JsonWriter.PRETTY_PRINT -> true).asJava
            .asInstanceOf[java.util.Map[String, Object]]))
    }
    case msg: StdOutLogLine => {
      // Something has emitted a STDOUT line
      Logger.trace(s"STDOUT from ${JsonWriter.objectToJson(sender)}")
      Logger.trace(
        JsonWriter.objectToJson(
          msg,
          Map(JsonWriter.PRETTY_PRINT -> true).asJava
            .asInstanceOf[java.util.Map[String, Object]]))
    }
  }
}

class FreeSpaceActor extends Actor {
  /*
  This actor checks whether free space exists to fulfill a request
  It checks all waiting jobs, all in progress jobs, and the quota specified in the config file
   */
  import Server.timeout

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
          .reduce((x, y) => x + y) //can't use sum, since Information is not Numeric
      }

      val spaceLeftOnDevice = Bytes(
        new File(config.local.recvPath).getTotalSpace)
      if (spaceLeftOnDevice - totalSpaceInTransit - req.req.space > Bytes(0)) { //is there space remaining in the quota
        sender ! true
      } else {
        sender ! false
      }
    }
  }
}
