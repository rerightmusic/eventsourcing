package eventsourcing.infra

import shared.postgres.all.given
import shared.newtypes.NewExtractor
import shared.principals.PrincipalId
import _root_.doobie.util.{Read, Put, Get, Write}
import org.tpolecat.typename.TypeName
import java.util.UUID
import java.time.OffsetDateTime
import eventsourcing.domain.types.SequenceId
import shared.json.all.*
case class ReadPostgresDocument[Id, Meta, Data](
  sequenceId: SequenceId,
  id: Id,
  meta: Meta,
  createdBy: PrincipalId,
  created: OffsetDateTime,
  version: Int,
  deleted: Boolean,
  data: Data
)

object ReadPostgresDocument:
  given readPostgresDocument[Id, Meta, Data](using
    ex: NewExtractor.Aux[SequenceId, Int],
    ex2: NewExtractor.Aux[Id, UUID],
    dec: JsonDecoder[Meta],
    tt: TypeName[Meta],
    dec2: JsonDecoder[Data],
    tt2: TypeName[Data]
  ): Read[ReadPostgresDocument[Id, Meta, Data]] =
    Read[
      (
        Int,
        UUID,
        Json,
        PrincipalId,
        OffsetDateTime,
        Int,
        Boolean,
        Json
      )
    ]
      .map { case (seqId, id, meta, crPrId, created, version, deleted, data) =>
        ReadPostgresDocument[Id, Meta, Data](
          ex.to(seqId),
          ex2.to(id),
          meta
            .as[Meta]
            .fold(
              err =>
                throw new Exception(
                  s"Failed to parse ${tt.value} from $meta Error: ${err}"
                ),
              v => v
            ),
          crPrId,
          created,
          version,
          deleted,
          data
            .as[Data]
            .fold(
              err =>
                throw new Exception(
                  s"Failed to parse ${tt2.value} from $data Error: ${err}"
                ),
              v => v
            )
        )
      }

case class WritePostgresDocument[Id, Meta, Data](
  id: Id,
  meta: Meta,
  createdBy: PrincipalId,
  created: OffsetDateTime,
  version: Int,
  deleted: Boolean,
  data: Data
)

object WritePostgresDocument:
  given writePostgresDocument[Id, Meta, Data](using
    ex2: NewExtractor.Aux[Id, UUID],
    en: JsonEncoder[Meta],
    en2: JsonEncoder[Data]
  ): Write[WritePostgresDocument[Id, Meta, Data]] =
    Write[
      (
        UUID,
        Json,
        PrincipalId,
        OffsetDateTime,
        Int,
        Boolean,
        Json
      )
    ]
      .contramap {
        case WritePostgresDocument(
              id,
              meta,
              crPrId,
              created,
              version,
              deleted,
              data
            ) =>
          (
            ex2.from(id),
            meta.toJsonASTOrFail,
            crPrId,
            created,
            version,
            deleted,
            data.toJsonASTOrFail
          )
      }
