package mortar.util

import java.nio.file.Path

import mortar.spec.ApplicationConfig

import com.lambdista.config.exception.{ConfigSyntaxException, ConversionException}
import org.pmw.tinylog.Logger

import com.typesafe.config.{Config => TSConfig, ConfigFactory}
import com.lambdista.config.Config
import com.lambdista.config.typesafe._


object Util {
  def config(fpath: Path): ApplicationConfig = {
    //grab the config
    val config = try {
      Config.from(ConfigFactory.parseFile(fpath.toFile).resolve())
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
        Logger.error("Could not parse into application DSL, not valid config file")
        Logger.error(e)
        throw e
      case e: Throwable =>
        Logger.error("Some error occured")
        Logger.error(e)
        throw e
    }
    config
  }
}
