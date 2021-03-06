package mortar

import java.nio.file.Paths

import mortar.server.Server
import mortar.util.Util
import net.sourceforge.argparse4j.ArgumentParsers
import org.pmw.tinylog.Logger

import scala.io.StdIn

object Mortar {

  def main(args: Array[String]): Unit = {
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

    val config = Util.MortarConfig(Paths.get("application.conf"))
    Logger.trace("Config parsed")

    val mortar_authorized_hosts = config.remote
      .map(machine =>
        s"ssh-rsa ${machine.pubkey} ${machine.uname}@${machine.hostname}")
      .reduce((x, y) => x + "\n" + y) //Build the authorized_keys file
    /*val authorized_keys = new File(config.local.sec.ssh.authorized_keys)
    val old_auth_keys = Source.fromFile(authorized_keys).mkString
    val old_auth_keys_file = new File(config.local.sec.ssh.authorized_keys + ".before_mortar")
    if (authorized_keys.renameTo(old_auth_keys_file)) {
      val new_auth_hosts = old_auth_keys + "\n" + mortar_authorized_hosts
      new PrintWriter(authorized_keys).write(new_auth_hosts).close
    } else {

    }*/

    val server = new Server(config)
    server.start()
    val line = StdIn.readLine() //TODO replace
    println(line)
    server.stop()

    /*
    if (authorized_keys.delete()) {

    } else {

    }

    if (old_auth_keys_file.renameTo(authorized_keys)) {

    } else {

    }*/
  }
}
