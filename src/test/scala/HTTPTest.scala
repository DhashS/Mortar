import org.scalatest.{Matchers, WordSpec}
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.testkit.ScalatestRouteTest
import akka.http.scaladsl.server._
import Directives._
import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.testkit.TestKit
import akka.util.Timeout
import mortar.test.shared.SharedTestInvariants
import scala.concurrent.duration._
/*
class HTTPServerTestInvariants
  extends SharedTestInvariants {
  //override def afterAll(): Unit = super.afterAll()
}

class HTTPServerTest
  extends TestKit(ActorSystem("HTTPTest"))
    with SharedTestInvariants
    with ScalatestRouteTest {
  override implicit val system = super[TestKit].system


  override def afterEach(): Unit = super.afterAll()
  "The HTTP endpoint" must {
    "provide the pubkey at /pubkey" in {
      Get("/pubkey") ~> srv.routes() ~> check {
        responseAs[String] shouldEqual "Poop"
      }
    }
  }

}
*/