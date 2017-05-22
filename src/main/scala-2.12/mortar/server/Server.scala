package mortar.server

import java.io.File

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
import mortar.util.ConfigActor
import mortar.util.Util.getConfig
import org.pmw.tinylog.Logger
import squants.information.Bytes

import scala.collection.JavaConverters._
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Promise}
import scala.io.StdIn
import scala.sys.process.{Process, ProcessLogger}
import scala.util.Random

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
  system.actorOf(Props[RDiffRouterActor], "router-actor")

}
class Server(config: ApplicationConfig)
    extends Directives
    with MortarJsonSupport {

  /*
  This class is the route definition and request validation for the HTTP server.
   */

  import Server._
  system.actorOf(Props(new ConfigActor(config)), "config-actor")
  loggingActor ! LogLine("Actors initialized")

  def routes(): Route = {
    val route =
      path("connect") {
        post {
          entity(as[MortarRequest]) { req =>
            //TODO don't validate against the key in the request, validate by GETting the requestor's /pubkey route
            config.remote.find(x => x.pubkey == req.key) match {
              case Some(client_config) => {
                val isSpace =
                  Await.result((spaceActor ? SpaceRequest(client_config, req))
                                 .mapTo[Boolean],
                               60.seconds)
                if (isSpace) {
                  // generate a distinct id for the request
                  //TODO wrap this in a future so it can async
                  val id = Promise[Int]

                  val inProcessIds =
                    Await.result((waitingActor ? MachineRequest)
                                   .mapTo[List[RDiffRequest]]
                                   .map(lst => lst.map(_.id)),
                                 60.seconds)
                  while (!id.isCompleted) {
                    val try_id = Random.nextInt()
                    inProcessIds.find(_ == try_id) match {
                      case None =>
                        id success try_id
                      case Some(_) => {} // duplicate ID
                    }
                  }
                  client_config.security match {
                    case "container" =>
                      complete(client_config.toString) //TODO remove magic string
                    case "sync" =>
                      //TODO support different backends
                      waitingActor ! RDiffRequest(
                        client_config,
                        req,
                        Await.result(id.future, 60.seconds)) //Add to Actor system queue
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
      } ~
        path("pubkey") {
          get {
            complete(config.local.sec.ssh.pub)
          }
        }
    route
  }
  def start(): Unit = {
    val bind = Http()
      .bindAndHandle(routes(), "localhost", config.app.port) //TODO Https
    loggingActor ! LogLine(s"Server started on port ${config.app.port}")
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
  def add(command: NewJob): WaitingJobsState =
    copy(command.data :: commands)
  def remove(command: JobDone): WaitingJobsState =
    copy(commands.dropWhile(_.id == command.data.id))

  def size: Int = commands.length
  override def toString: String = commands.toString
}
class WaitingActor extends PersistentActor with ActorLogging {
  /*
  This actor manages the persistent state of the application.
  It keeps an ordered list of to-be-filled requests, so that if the application is restarted
  the requests are restarted as well
   */
  import Server.timeout

  override def persistenceId = "waiting-actor"
  private var state = WaitingJobsState()
  private val RDiffRouterActor = Await.result(
    context.actorSelection("/user/router-actor").resolveOne,
    60.seconds)

  def AddJob(command: NewJob): Unit =
    state = state.add(command)

  def RemoveJob(command: JobDone): Unit =
    state = state.remove(command)

  val receiveRecover: Receive = {
    case evt: NewJob => AddJob(evt)
    case evt: JobDone => RemoveJob(evt)
    case SnapshotOffer(_, snapshot: WaitingJobsState) => state = snapshot
  }
  val receiveCommand: Receive = {
    case job: RDiffRequest => {
      persist(NewJob(job)) { event =>
        log.debug(s"Persisting message ${JsonWriter
          .objectToJson(job,
                        Map(JsonWriter.PRETTY_PRINT -> true).asJava
                          .asInstanceOf[java.util.Map[String, Object]])}")
        AddJob(event)
        saveSnapshot(state)
        context.system.eventStream.publish(event)
        log.debug(s"Message persisted!")
        RDiffRouterActor ! job
        log.debug(
          s"Job id ${job.id} for machine ${job.machine.hostname} added to queue.")

      }
    }
    case done: RDiffDone => {
      /*
      A RDiff job has completed, this logs it and removes it from the persistent state
       */
      val donejob = done.req
      persist(JobDone(donejob)) { event =>
        log.debug(s"Persisting message ${JsonWriter
          .objectToJson(donejob,
                        Map(JsonWriter.PRETTY_PRINT -> true).asJava
                          .asInstanceOf[java.util.Map[String, Object]])}")
        RemoveJob(event)
        saveSnapshot(state)
        context.system.eventStream.publish(event)
        log.debug("Message Persisted!")
        log.debug(
          s"Job id ${donejob.id} for machine ${donejob.machine.hostname} done!")
      }
    } //TODO state modification
    case MachineRequest => {
      /*
      This relays the persistent state to actors who ask, such as for space computation
       */
      sender ! state.commands
    }
  }
}

class RDiffRouterActor extends Actor with ActorLogging {
  /*
  This actor is a router that spins a transfer actor per host and distributes jobs to them
   */

  private val config = getConfig

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

class RDiffActor extends Actor with ActorLogging {
  /*
  This actor is spun up for each host, and manages transfer requests for that host
  It currently manages a single RDiff job, and its associated failure modes
   */

  import Server.timeout

  private val config = getConfig

  override def receive: Receive = {
    //rdr and rdp pertain to the actual rdiff-backup
    case rdr: RDiffRequest => {
      /*
      This manages a remote rdiff job to local storage
       */

      val mortarWaitingActor = Await.result(
        context.actorSelection("/user/waiting-actor").resolveOne,
        60.seconds)
      val logger = ProcessLogger(
        line => log.info(s"STDOUT for ${rdr.machine.hostname}: $line"),
        line => log.error(s"STDERR for ${rdr.machine.hostname}: $line"))
      val rdp = Process(
        s"rdiff-backup -b ${rdr.machine.uname}@${rdr.machine.hostname}::${rdr.req.path} "
          + s"${config.local.recvPath}")

      try {
        log.debug(
          s"Starting RDiff job id ${rdr.id} for machine ${rdr.machine.hostname}")
        val proc = rdp.run(logger)
        val exit = proc.exitValue()
        if (exit != 0) {
          val err =
            s"rdiff-backup request for ${rdr.machine.hostname}::${rdr.req.path} exited with code $exit, check STDERR"
          mortarWaitingActor ! RDiffFailure(rdr)
          log.error(err)
        } else {
          mortarWaitingActor ! RDiffDone(rdr)
          log.info(
            s"RDiff job for ${rdr.machine.hostname} completed successfully")
        }
      } catch {
        case e: Exception => {
          log.error(s"RDiff job for ${rdr.machine.hostname} threw exception",
                    e)
          mortarWaitingActor ! RDiffFailure(rdr)
        }
      }
    }
  }
}

class LogActor extends Actor with ActorLogging {
  /*
  This actor gives me a centralized way to do logging, useful for non-actor logging
  It currently encapsulates ActorLogging
   */
  override def receive: Receive = {
    case msg: LogLine => {
      log.info(msg.line)
    }
  }
}

class FreeSpaceActor extends Actor with ActorLogging {
  /*
  This actor checks whether free space exists to fulfill a request
  It checks all waiting jobs, all in progress jobs, and the quota specified in the config file
   */
  import Server.timeout

  private val config = getConfig

  override def receive: Receive = {
    case req: SpaceRequest => {
      val mortarWaitingActor = Await.result(
        context.actorSelection("/user/waiting-actor").resolveOne(),
        60.seconds)

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
      val left = spaceLeftOnDevice - totalSpaceInTransit - req.req.space
      if (left > Bytes(0)) { //is there space remaining in the quota
        log.debug(s"$left space left on device")
        sender ! true
      } else {
        log.error("No space left in on device!")
        sender ! false
      }
    }
  }
}
