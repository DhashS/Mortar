package mortar.test.shared

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

import org.scalatest.BeforeAndAfterEach
import Directives._

trait SharedTestInvariants
  extends WordSpecLike
    with Matchers
    with ScalaFutures
    //with BeforeAndAfterEach
    with BeforeAndAfterAll {

  val config = Util.MortarConfig(Paths.get("application.conf"))

  var srv: Server = _

  override def beforeAll(): Unit = {
    super.beforeAll()
    srv = new Server(config)
    srv.start()
  }

  override def afterAll(): Unit = {
    super.afterAll()
    srv.stop()
  }
}

