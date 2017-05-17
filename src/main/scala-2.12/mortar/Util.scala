package mortar.util

import java.io.File
import java.nio.file.Path

import mortar.spec._
import com.lambdista.config.exception.{ConfigSyntaxException, ConversionException}
import org.pmw.tinylog.Logger
import com.typesafe.config.{ConfigFactory, Config => TSConfig}
import com.lambdista.config.Config
import com.lambdista.config.typesafe._
import com.cedarsoftware.util.io.JsonWriter
import squants.information.{Bytes, Information}
import akka.actor.Actor
import akka.pattern.ask
import akka.util.Timeout

import scala.concurrent.{Await, Future}
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
            s"Unexpected security type on client ${JsonWriter.objectToJson(cli)}\n key security must be either container or sync"
          Logger.error(e)
          throw new ConfigSyntaxException(e)
      }
    }
    config
  }
  def FreeSpace(cfg: ApplicationConfig, machine: RemoteMachine): Boolean = {
    //TODO: add an in-transit flag
    //So I can resolve the race condition between multiple data dumps
    Information(cfg.local.maxSpace).get - Bytes(
      new File(cfg.local.recvPath).getTotalSpace)
    return true
  }
}


