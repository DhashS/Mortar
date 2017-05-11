package mortar.util

import java.nio.file.Path

import mortar.spec.ApplicationConfig
import com.lambdista.config.exception.{
  ConfigSyntaxException,
  ConversionException
}
import org.pmw.tinylog.Logger
import com.typesafe.config.{ConfigFactory, Config => TSConfig}
import com.lambdista.config.Config
import com.lambdista.config.typesafe._
import com.cedarsoftware.util.io.JsonWriter

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
}
