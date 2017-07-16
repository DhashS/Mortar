package mortar.test.server

import akka.testkit.{ImplicitSender, TestActors, TestKit}
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpecLike}
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.concurrent.PatienceConfiguration
import mortar.server.Server
import mortar.util.Util
import java.nio.file.Paths

import scala.io.Source
import java.io.File

import akka.pattern.ask
import akka.util.Timeout
import mortar.spec._
import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.stream.ActorMaterializer

import scala.concurrent.{Await, Future}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.testkit.ScalatestRouteTest
import akka.http.scaladsl.server._
import Directives._

import mortar.test.shared.SharedTestInvariants

trait ServerTestkitInvariants extends SharedTestInvariants {}

class ServerTest
    extends TestKit(ActorSystem("ServerTest"))
    with ServerTestkitInvariants
    with ImplicitSender {

  implicit val timeout = Timeout(5.seconds)
  implicit val materializer = ActorMaterializer()

  "The server actors" must {
    "be able to provide the configuration" in {
      srv.configActor ! ConfigRequest()
      expectMsg(10.seconds, config)
    }
    "be able to provide the pubkey" in {
      val my_key = Source
        .fromFile(new File(config.local.sec.ssh.pub))
        .mkString
        .trim
      srv.pubkeyActor ! PubkeyRequest()
      expectMsg(10.seconds, my_key)
    }
  }/*
  "The backup system" must {
    "be able to accept a valid request" in {

    }
    ""
  }*/

  override def afterAll(): Unit = {
    super.afterAll()
    TestKit.shutdownActorSystem(system)
  }
}
