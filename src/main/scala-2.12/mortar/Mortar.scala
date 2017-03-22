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

/*
  def freeSpace(them: RemoteMachine, size: Information): Boolean = {
    //check self
    //check quota
    //send remote check
  }
  def allocateSpace(them: RemoteMachine, size: Information): Boolean = {
    //build container on self
    //send allocate request
  }

  def deAllocateSpace(them: RemoteMachine): Boolean = {
    //Something went wrong
    //deallocate space on self
    //send deallocation message
  }
  def checkIfSigned(them: RemoteMachine, msg: SignedMessage): Message = {
    //check against pubkey
  }
  def handleMessage(msg: Message) = {
    //state machine
  }
  def allocateContainer(name: String)(implicit val sec: Security)
 */
