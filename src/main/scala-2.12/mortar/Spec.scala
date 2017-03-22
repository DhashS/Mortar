package mortar.spec

import squants.information.{Bytes, Information}
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.marshalling.ToEntityMarshaller
import akka.http.scaladsl.unmarshalling.FromEntityUnmarshaller
import spray.json._

//Case classes to define application
case class ApplicationConfig(app: AppConfig,
                             remote: List[RemoteMachine],
                             local: LocalMachine) //,
//rsync: RSyncConfig)

case class AppConfig(port: Int, maxSpace: Option[String])

case class Security(luksCipher: String,
                    luksHash: String,
                    leakSize: Boolean,
                    ssh: SSHConfig)

case class SSHConfig(pub: String, priv: String)

case class RemoteMachine(hostname: String,
                         interval: String,
                         port: Int,
                         toFile: Option[String],
                         recvFile: Option[String],
                         pubkey: String)

case class LocalMachine(uname: String,
                        recvPath: String,
                        toShoot: List[String],
                        sec: Security,
                        maxSpace: String,
                        known_hosts: Option[Boolean]) //TODO

case class RSyncConfig(opts: Option[List[String]])

//Web request safe SerDes
final case class FreeSpace(space: Information) {
  implicit object FreeSpaceFormat extends RootJsonFormat[FreeSpace] {
    override def read(json: JsValue): FreeSpace = json match {
      case JsObject(obj) =>
        obj
          .get("space")
          .asInstanceOf[Option[Double]] match {
          case Some(size) => FreeSpace(Bytes(size))
          case None =>
            deserializationError(
              s"space field not in FreeSpace map, got ${obj.toMap} instead")
        }
      case x => deserializationError(s"Need an Information in Bytes, got ${x}")
    }
    override def write(obj: FreeSpace) =
      JsObject(Map("space" -> JsNumber(obj.space.toBytes)))
  }
  def -(that: FreeSpace): FreeSpace = {
    FreeSpace(this.space - that.space)
  }
}

final case class PubKey(key: String)
final case class SpaceRequest(pub: String, space: Information)
final case class AllocateResponse(space: Information, status: Boolean)
final case class SynchronizeResponse()

trait MortarJsonSupport extends SprayJsonSupport with DefaultJsonProtocol {
  implicit object InformationJsonFormat extends RootJsonFormat[Information] {
    override def read(json: JsValue): Information = json match {
      case JsNumber(size) => Bytes(size)
      case _ => deserializationError("Need an Information")
    }

    override def write(obj: Information) = JsNumber(obj.toBytes)
  }
  implicit val FreeSpaceFmt = jsonFormat(FreeSpace, "space")
  implicit val PubKeyFmt = jsonFormat1(PubKey)
  implicit val SpaceRequestFmt = jsonFormat2(SpaceRequest)
  implicit val AllocateResponseFmt = jsonFormat2(AllocateResponse)
}
