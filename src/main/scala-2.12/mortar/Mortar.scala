package mortar.app

import java.nio.file.Paths

import net.sourceforge.argparse4j.ArgumentParsers
import org.pmw.tinylog.Logger
import mortar.util.{Util => util}

import mortar.server.Server

object Mortar {
  def main(args: Array[String]) {
    //set up argparse
    val parser = ArgumentParsers
      .newArgumentParser("mortar")
      .description("Sling large files")
    parser
      .addArgument("--config")
      .metavar("c")
      .help("The config file")
    val namespace = parser.parseArgs(args)
    Logger.trace(s"Arguments parsed, ${namespace.toString}")

    //TODO config modification argument

    val config = util.config(Paths.get("application.conf"))

    val server = new Server(config)
    server.start()
  }
}
