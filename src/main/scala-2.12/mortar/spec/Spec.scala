package mortar.spec

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.routing.ConsistentHashingRouter.ConsistentHashable
import spray.json._
import squants.information.{Bytes, Information}

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

case class SSHConfig(pub: String, priv: String, authorized_keys: String)

case class RemoteMachine(uname: String,
                         hostname: String,
                         security: String, //container, bare TODO move to request
                         toFile: Option[String],
                         recvFile: Option[String],
                         pubkey: String)

case class LocalMachine(uname: String,
                        recvPath: String,
                        sec: ServerSecurity,
                        maxSpace: String,
                        known_hosts: Option[Boolean]) //TODO

final case class MortarRequest(key: String, space: Information, path: String) // TODO incorporate security


case class RDiffRequest(machine: RemoteMachine, req: MortarRequest) extends ConsistentHashable {
  override def consistentHashKey: String = machine.pubkey
}

case class RDiffDone(req: RDiffRequest)
case class RDiffFailure(machine: RemoteMachine, e: Exception)
case class MachineRequest()
case class ConfigRequest()
case class SpaceRequest(machine: RemoteMachine, req: MortarRequest)
case class StdOutLogLine(line: String)
case class StdErrLogLine(line: String)
case class Cmd(data: RemoteMachine)
case class Evt(data: RDiffRequest)


trait MortarJsonSupport extends SprayJsonSupport with DefaultJsonProtocol {
  implicit object InformationJsonFormat extends RootJsonFormat[Information] {
    override def read(json: JsValue): Information = json match {
      case JsNumber(size) => Bytes(size)
      case _ => deserializationError("Need an Information, provide a bare JSON number of Bytes")
    }

    override def write(obj: Information) = JsNumber(obj.toBytes)
  }
  implicit val MortarRequestFmt = jsonFormat3(MortarRequest)
}
