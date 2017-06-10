package mortar.util

import java.io.File
import java.nio.file.Path

import akka.actor.{Actor, ActorContext, ActorLogging}
import akka.pattern.ask
import com.cedarsoftware.util.io.JsonWriter
import com.lambdista.config.Config
import com.lambdista.config.exception.{
  ConfigSyntaxException,
  ConversionException
}
import com.lambdista.config.typesafe._
import com.typesafe.config.ConfigFactory
import mortar.server.Server
import mortar.spec._
import org.pmw.tinylog.Logger

import scala.concurrent.Await
import scala.concurrent.duration._

object Util {
  def config(fpath: Path): ApplicationConfig = {
    //grab the config
    val config = try {
      Config
        .from(ConfigFactory.parseFile(fpath.toFile).resolve())
        .get
        .as[ApplicationConfig]
        .get
    } catch {
      case e: ConfigSyntaxException =>
        Logger.error("Could not parse application.conf, not valid HOCON")
        Logger.error(e)
        e.printStackTrace()
        throw e
      case e: ConversionException =>
        Logger.error(
          "Could not parse into application DSL, not valid config file")
        Logger.error(e)
        throw e
      case e: Throwable =>
        Logger.error("Some error occured")
        Logger.error(e)
        throw e
    }
    for (cli <- config.remote) {
      cli.security match {
        case "container" =>
        case "sync" =>
        case _ =>
          val e =
            s"Unexpected security type on client ${Json.fromObject(cli)}\n key security must be either container or sync"
          Logger.error(e)
          throw new ConfigSyntaxException(e)
      }
    }
    val recv = new File(config.local.recvPath)
    if (!(recv.exists && recv.isDirectory)) {
      throw new ConfigSyntaxException(
        s"No recieving path folder exists at ${config.local.recvPath}")
    }
    val backup_path = new File(config.local.backupPath)
    if (!(backup_path.exists && backup_path.isDirectory)) {
      throw new ConfigSyntaxException(s"The folder which is to be backed up (${config.local.backupPath}) does not exist!")
    }
    config
  }
  def getConfig(implicit context: ActorContext): ApplicationConfig = {
    import Server._

    Await.result(context
                   .actorSelection("/user/config-actor")
                   .resolveOne
                   .flatMap(_ ? ConfigRequest)
                   .mapTo[ApplicationConfig],
                 60.seconds)
  }
}

class ConfigActor(config: ApplicationConfig) extends Actor with ActorLogging {
  /*
  This is a simple actor to distribute the application configuration object on demand to other actors
   */
  override def receive: Receive = {
    case ConfigRequest => {
      sender ! config
    }
  }
}

object Json {
  import scala.collection.JavaConverters.mapAsJavaMap
  def fromObject(obj: Object): String = {
    JsonWriter.objectToJson(obj,
                            mapAsJavaMap(Map(JsonWriter.PRETTY_PRINT -> true))
                              .asInstanceOf[java.util.Map[String, Object]])
  }
}
