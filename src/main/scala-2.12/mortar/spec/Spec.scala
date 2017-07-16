package mortar.spec

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.routing.ConsistentHashingRouter.ConsistentHashable
import spray.json._
import squants.information.{Bytes, Information}

import java.time.Instant
import java.util.UUID

//Case classes to define application
case class ApplicationConfig(app: AppConfig,
                             remote: List[RemoteMachine],
                             local: LocalMachine) //,
//rsync: RSyncConfig)

case class AppConfig(port: Int,
                     maxSpace: Option[String], // In Squants units
                     timeout: Int) // In seconds

case class ServerSecurity(luksCipher: String,
                          luksHash: String,
                          leakSize: Boolean,
                          ssh: SSHConfig)

case class SSHConfig(pub: String, priv: String, authorized_keys: String)

case class RemoteMachine(
    uname: String,
    hostname: String,
    security: String, //container, bare TODO move to request
    toFile: Option[String],
    recvFile: Option[String],
    pubkey: String)

case class LocalMachine(uname: String,
                        recvPath: String,
                        backupPath: String,
                        sec: ServerSecurity,
                        maxSpace: String,
                        known_hosts: Option[Boolean]) //TODO

final case class MortarRequest(key: String,
                               space: Information,
                               path: String,
                               id: UUID)
final case class MortarStatus(key: String, id: UUID)

case class RDiffRequest(machine: RemoteMachine, req: MortarRequest)
    extends ConsistentHashable {
  override def consistentHashKey: String = machine.pubkey
}

case class TransferDone(req: RDiffRequest)
case class TransferFailure(req: RDiffRequest)

case class MachineRequest()

abstract class Echo
case class ConfigRequest() extends Echo
case class PubkeyRequest() extends Echo


case class SpaceRequest(machine: RemoteMachine, req: MortarRequest)
case class NewJob(data: RDiffRequest)
case class JobDone(data: RDiffRequest)
case class LogLine(line: String)

case class BackupStatus(machine: RemoteMachine, when: Instant)

case class BackupDone(machine: RemoteMachine)
case class BackupStarted(machine: RemoteMachine)
case class StartBackupJob(machine: RemoteMachine)

trait MortarJsonSupport extends SprayJsonSupport with DefaultJsonProtocol {
  implicit object InformationJsonFormat extends RootJsonFormat[Information] {
    override def read(json: JsValue): Information = json match {
      case JsNumber(size) => Bytes(size)
      case _ =>
        throw deserializationError(
          "Need an Information, provide a bare JSON number of Bytes")
    }

    override def write(obj: Information) = JsNumber(obj.toBytes)
  }
  implicit object UUIDJsonFormat extends RootJsonFormat[UUID] {
    override def read(json: JsValue): UUID = json match {
      case JsString(uuid) => UUID.fromString(uuid)
      case _ =>
        throw deserializationError(
          "Need a UUID, provide a bare JSON string that is a UUID")
    }

    override def write(obj: UUID) = JsString(obj.toString)
  }
  implicit val MortarRequestFmt = jsonFormat4(MortarRequest)
  implicit val MortarStatusFmt = jsonFormat2(MortarStatus)
}
