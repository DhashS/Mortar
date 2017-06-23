package mortar.server

import java.io.{File, IOException}
import java.time.Instant

import akka.actor.{Actor, ActorSystem, _}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.MediaTypes._
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.BasicHttpCredentials
import akka.http.scaladsl.server.{Directives, Route}
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.pattern.ask
import akka.persistence._
import akka.routing.ConsistentHashingPool
import akka.stream.ActorMaterializer
import akka.util.Timeout
import mortar.spec._
import mortar.util.Util.getConfig
import mortar.util.{EchoActor, Json}
import org.pmw.tinylog.Logger
import spray.json._
import squants.information.Bytes

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.Promise
import scala.io.{Source, StdIn}
import scala.sys.process.{Process, ProcessLogger}
import java.util.UUID

import scala.util.{Try, Success, Failure}

import akka.http.scaladsl.Http.ServerBinding

object Server {
  // needed to run the route
  implicit val system = ActorSystem("mortar-http-server-actorsystem")
  implicit val materializer = ActorMaterializer()
  // needed for the future map/flatmap in the end
  implicit val executionContext: ExecutionContext = system.dispatcher
  implicit val timeout = Timeout(60.seconds)

  val backupHandlerActor =
    system.actorOf(Props[BackupHandlerActor], "backup-handler-actor")
  val waitingActor =
    system.actorOf(Props[WaitingActor], "waiting-actor")
  val loggingActor = system.actorOf(Props[LogActor], "logging-actor")
  system.actorOf(Props[JobRouterActor], "router-actor")
  val spaceActor =
    system.actorOf(Props[FreeSpaceActor], "free-space-actor")

}
class Server(config: ApplicationConfig)
    extends Directives
    with MortarJsonSupport {

  /*
  This class is the route definition and request validation for the HTTP server.
   */

  import Server._
  val configActor = system.actorOf(Props(new EchoActor[ConfigRequest](config)),
                                   "config-actor") // Spin up configuration
  val pubkeyActor = system.actorOf(
    Props(
      new EchoActor[PubkeyRequest](
        Source
          .fromFile(new File(config.local.sec.ssh.pub))
          .mkString
          .trim)))

  // The kickstart loop for Quartz-scheduler to handle backups

  //config.remote.map( machine =>
//
  //)

  loggingActor ! LogLine("Actors initialized")

  val binder = Promise[Future[ServerBinding]]

  def start(): Unit = {
    val bind: Future[ServerBinding] = Http()
      .bindAndHandle(routes(), "localhost", config.app.port) //TODO Https
    loggingActor ! LogLine(s"Server started on port ${config.app.port}")
    binder success bind
  }

  def stop(): Unit = {
    loggingActor ! LogLine("Stopping server")
    binder.future onComplete {
      case Success(bind) =>
        bind.flatMap(_.unbind()).onComplete(_ => system.terminate())
      case Failure(e) =>
        throw e
    }
  }

  def routes(): Route = {
    val route =
      path("backup") {
        post {
          entity(as[MortarRequest]) { req =>
            config.remote.find(x => x.pubkey == req.key) match {
              case Some(machine) => {
                val isSpace =
                  Await.result((spaceActor ? SpaceRequest(machine, req))
                                 .mapTo[Boolean],
                               config.app.timeout.seconds)
                if (isSpace) {
                  // make sure there is a distinct id for the request, shared across client and server
                  val inProcessIds =
                    Await.result((waitingActor ? MachineRequest)
                                   .mapTo[List[RDiffRequest]]
                                   .map(lst => lst.map(_.req.id)),
                                 config.app.timeout.seconds)
                  if (inProcessIds.contains(req.id)) {
                    complete { HttpResponse(StatusCodes.Locked) } // There is an ID conflict, it's up to the client to resolve
                  }
                  machine.security match {
                    case "container" =>
                      complete(machine.toString) //TODO remove magic string
                    case "sync" =>
                      //TODO support different backends
                      waitingActor ! RDiffRequest(machine, req) //Add to Actor system queue
                      complete { HttpResponse(StatusCodes.Accepted) }
                    case _ =>
                      complete { machine.toString } //echo since this should never happen
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
        path("backup" / "status") {
          //TODO auth
          //TODO test
          post {
            entity(as[MortarStatus]) { sreq =>
              val req_uuids = Await
                .result((waitingActor ? MachineRequest)
                          .mapTo[List[RDiffRequest]]
                          .map[List[UUID]](lst => lst.map(_.req.id)),
                        config.app.timeout.seconds)
                .find(_ == sreq.id)

              req_uuids match {
                case Some(uid) => complete(StatusCodes.Found)
                case None =>
                  complete(StatusCodes.NotFound) //TODO does this work
              }
            }
          }
        } ~
        path("pubkey") {
          val my_key = Await
            .result((pubkeyActor ? PubkeyRequest).mapTo[String],
                    config.app.timeout.seconds)
          complete {
            HttpResponse(StatusCodes.OK, entity = my_key)
          }
        } ~
        path("debug") {
          backupHandlerActor ! StartBackupJob(config.remote.head)
          complete("OK")
        }
    route
  }
}

case class WaitingJobsState(commands: List[RDiffRequest] = Nil) {
  /*
  This class encapsulates the persistent state of the application's response
  to others backing up to it
   */
  def add(command: NewJob): WaitingJobsState =
    copy(command.data :: commands)
  def remove(command: JobDone): WaitingJobsState =
    copy(commands.dropWhile(_.req.id == command.data.req.id))

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
  val config = getConfig

  val receiveRecover: Receive = {
    case evt: NewJob => AddJob(evt)
    case evt: JobDone => RemoveJob(evt)
    case SnapshotOffer(_, snapshot: WaitingJobsState) => state = snapshot
  }
  val receiveCommand: Receive = {
    case job: RDiffRequest => {
      persist(NewJob(job)) { event =>
        log.debug(s"Persisting message ${Json.fromObject(job)}")
        AddJob(event)
        JobRouter ! job
        saveSnapshot(state)
        log.debug(s"Message persisted!")
        log.debug(
          s"Job id ${job.req.id} for machine ${job.machine.hostname} added to queue.")

      }
    }
    case done: TransferDone => {
      /*
      A RDiff job has completed, this logs it and removes it from the persistent state
       */
      val donejob = done.req
      persist(JobDone(donejob)) { event =>
        log.debug(s"Persisting message ${Json.fromObject(donejob)}")
        RemoveJob(event)
        saveSnapshot(state)
        log.debug("Message Persisted!")
        log.debug(
          s"Job id ${donejob.req.id} for machine ${donejob.machine.hostname} done!")
      }
    } //TODO state modification
    case MachineRequest => {
      /*
      This relays the persistent state to actors who ask, such as for space computation
       */
      sender ! state.commands
    }
  }
  private val JobRouter = Await.result(
    context.actorSelection("/user/router-actor").resolveOne,
    config.app.timeout.seconds)
  private var state = WaitingJobsState()

  override def persistenceId = "waiting-actor"

  def AddJob(command: NewJob): Unit =
    state = state.add(command)

  def RemoveJob(command: JobDone): Unit =
    state = state.remove(command)
}

case class BackupState(
    commands: (List[BackupStatus], List[BackupStatus]) = (Nil, Nil)) {
  /*
  This class encapsulates the persistent state of the local machine's backups to
  remote machines.
  The first list is ones that aren't scheduled for backups yet, and the second list
  is one that are in the progress of backing up.
   */
  def addAllFromConfig(config: ApplicationConfig): BackupState = {
    /*
    This is a convinience method to populate the initial state of the backups,
    when all backups are waiting and none have been started yet
     */
    val machines = for (machine <- config.remote) yield {
      BackupStatus(machine, Instant.now())
    }
    copy((machines, Nil))
  }

  def start(command: BackupStarted): BackupState = {
    /*
    This method adds a started backup, by removing it from the first list and
    adding it to the second
     */
    copy(
      (commands._1.dropWhile(_.machine == command.machine),
       BackupStatus(command.machine, Instant.now()) :: commands._2))
  }

  def done(command: BackupDone): BackupState = {
    /*
    This method finishes a backup, by removing it from the second list and adding it
    back to the first list
     */
    copy(
      (BackupStatus(command.machine, Instant.now()) :: commands._1,
       commands._2.dropWhile(_.machine == command.machine)))
  }
}

class BackupHandlerActor
    extends PersistentActor
    with ActorLogging
    with MortarJsonSupport {
  /*
  This actor is responsible for starting a backup from the current machine (client) to another, remote machine
  (server). This handles request creation and auth, and starts a backup request to a remote machine only via an
  external scheduler.
   */
  import Server._

  val receiveRecover: Receive = {
    case evt: BackupStarted => AddJob(evt)
    case evt: BackupDone => RemoveJob(evt)
    case SnapshotOffer(_, snapshot: BackupState) => state = snapshot
  }
  val receiveCommand: Receive = {
    case job: StartBackupJob => {
      val config = getConfig
      println("recvd job request")
      val other_uri = s"http://${job.machine.hostname}:9000" //TODO port, but should be resolved by dockerizing
      val plain_pubkey_auth =
        headers.Authorization(
          BasicHttpCredentials(config.local.uname, config.local.sec.ssh.pub))
      val other_pubkey = Await.result(
        Http().singleRequest(
          HttpRequest(method = HttpMethods.GET,
                      uri = other_uri + "/pubkey",
                      headers = List(plain_pubkey_auth))),
        config.app.timeout.seconds) match {
        case HttpResponse(StatusCodes.OK, _, entity, _) =>
          Unmarshal(entity).to[String] //TODO does this get me pubkey
        case resp @ HttpResponse(code, _, msg, _) =>
          val err =
            s"Pubkey request to ${job.machine.hostname} failed with code $code, $msg, aborting backup request"
          log.warning(err)
          resp.discardEntityBytes()
          throw new IOException(err)
      }
      val my_key = Source.fromFile(config.local.sec.ssh.pub).mkString.trim

      val mortarReq = MortarRequest(key = my_key,
                                    space = Bytes(12),
                                    path = s"${config.local.backupPath}",
                                    id = UUID.randomUUID()) //TODO UUID
      val req_specific_auth =
        headers.Authorization(BasicHttpCredentials(
          config.local.uname,
          config.local.sec.ssh.pub)) //TODO swap with the job id signed with this machine's privkey and crypted with the other's pubkey

      Http().singleRequest(
        HttpRequest(
          method = HttpMethods.GET,
          uri = s"http://localhost:${config.app.port}/pubkey")) // TODO HTTPS
      // TODO HttpBasicAuth

      val built_req = mortarReq.toJson.toString // TODO request from local HTTP route rather than config

      val backup_resp = Await.result(
        Http().singleRequest(
          HttpRequest(
            method = HttpMethods.POST,
            uri = s"$other_uri/backup",
            //headers = List(req_specific_auth), TODO auth on the reciving side
            entity = HttpEntity(`application/json`, built_req)
          )),
        config.app.timeout.seconds
      ) match {
        case HttpResponse(StatusCodes.Accepted, _, _, _) => {
          log.debug("Backup request successfully submitted")
          persist(BackupStarted(job.machine)) { event =>
            log.debug(s"Persisting message ${Json.fromObject(event)}")
            AddJob(event)
            saveSnapshot(state)
            log.debug("Message persisted!")
            log.debug(
              s"Starting remote backup request to ${event.machine.hostname}")
          }
        }
        case HttpResponse(StatusCodes.Locked, _, _, _) => {
          log.debug("Retry immedeatley, all went well but the UUID is locked")
          self ! job
        }
        case HttpResponse(StatusCodes.InsufficientStorage, _, _, _) => {
          log.debug("no space left, send email")
        }
        case HttpResponse(StatusCodes.Forbidden, _, _, _) => {
          log.debug(
            "HTTP Basic auth failed, this will never work now, send an email")
        }
      }
    }
  }

  private var state = BackupState()

  override def persistenceId = "backup-handler"

  def AddJob(command: BackupStarted): Unit =
    state = state.start(command)

  def RemoveJob(command: BackupDone): Unit =
    state = state.done(command)
}

class JobRouterActor extends Actor with ActorLogging {
  /*
  This actor is a router that spins up a transfer actor per host and distributes jobs to them
   */

  private val config = getConfig

  private val router = context.actorOf(
    ConsistentHashingPool(config.remote.length).props(Props[TransferActor]),
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

class TransferActor extends Actor with ActorLogging {
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
        config.app.timeout.seconds)
      val logger = ProcessLogger(
        line => log.info(s"STDOUT for ${rdr.machine.hostname}: $line"),
        line => log.error(s"STDERR for ${rdr.machine.hostname}: $line"))
      val rdp = Process(
        s"rdiff-backup -b ${rdr.machine.uname}@${rdr.machine.hostname}::${rdr.req.path} "
          + s"${config.local.recvPath}")

      try {
        log.debug(
          s"Starting RDiff job id ${rdr.req.id} for machine ${rdr.machine.hostname}")
        val proc = rdp.run(logger)
        val exit = proc.exitValue()
        if (exit != 0) {
          val err =
            s"rdiff-backup request for ${rdr.machine.hostname}::${rdr.req.path} exited with code $exit, check STDERR"
          mortarWaitingActor ! TransferFailure(rdr)
          log.error(err)
        } else {
          mortarWaitingActor ! TransferDone(rdr)
          log.info(
            s"RDiff job for ${rdr.machine.hostname} completed successfully")
        }
      } catch {
        case e: Exception => {
          log.error(s"RDiff job for ${rdr.machine.hostname} threw exception",
                    e)
          mortarWaitingActor ! TransferFailure(rdr)
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
        config.app.timeout.seconds)

      var totalSpaceInTransit = Bytes(0)

      val inProgress = Await.result(
        (mortarWaitingActor ? MachineRequest).mapTo[List[RDiffRequest]],
        config.app.timeout.seconds)
      if (inProgress.nonEmpty) {
        totalSpaceInTransit = inProgress
          .map(x => x.req.space)
          .reduce((x, y) => x + y) //can't use sum, since Information is not Numeric
      }
      val spaceLeftOnDevice = Bytes(
        new File(config.local.recvPath).getTotalSpace)
      //TODO impliment quota from config.app.maxspace
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
