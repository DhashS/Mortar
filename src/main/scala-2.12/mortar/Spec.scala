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

case class ServerSecurity(luksCipher: String,
                          luksHash: String,
                          leakSize: Boolean,
                          ssh: SSHConfig)

case class SSHConfig(pub: String, priv: String)

case class RemoteMachine(hostname: String,
                         security: String, //container, bare
                         toFile: Option[String],
                         recvFile: Option[String],
                         pubkey: String)

case class LocalMachine(uname: String,
                        recvPath: String,
                        sec: ServerSecurity,
                        maxSpace: String,
                        known_hosts: Option[Boolean]) //TODO

final case class MortarRequest(key: String, space: Information, hash: String)

trait MortarJsonSupport extends SprayJsonSupport with DefaultJsonProtocol {
  implicit object InformationJsonFormat extends RootJsonFormat[Information] {
    override def read(json: JsValue): Information = json match {
      case JsNumber(size) => Bytes(size)
      case _ => deserializationError("Need an Information")
    }

    override def write(obj: Information) = JsNumber(obj.toBytes)
  }
  implicit val MortarRequestFmt = jsonFormat3(MortarRequest)
}
