package shared.postgres.schemaless

import shared.postgres.doobie.all.given
import shared.newtypes.NewExtractor
import shared.principals.PrincipalId
import _root_.doobie.util.{Read, Put, Get, Write}
import org.tpolecat.typename.TypeName
import java.util.UUID
import java.time.OffsetDateTime
import shared.json.all.{given, *}

case class PostgresDocument[Id, Meta, Data](
  id: Id,
  meta: Meta,
  createdBy: PrincipalId,
  lastUpdatedBy: PrincipalId,
  data: Data,
  schemaVersion: Int,
  deleted: Boolean,
  created: OffsetDateTime,
  lastUpdated: OffsetDateTime
)

object PostgresDocument:
  given readPostgresDocument[Id, Meta, Data](using
    ex: NewExtractor.Aux[Id, UUID],
    decMeta: JsonDecoder[Meta],
    ttMeta: TypeName[Meta],
    dec: JsonDecoder[Data],
    tt: TypeName[Data]
  ): Read[PostgresDocument[Id, Meta, Data]] =
    Read[
      (
        UUID,
        Json,
        PrincipalId,
        PrincipalId,
        Json,
        Int,
        Boolean,
        OffsetDateTime,
        OffsetDateTime
      )
    ]
      .map {
        case (
              id,
              meta,
              crPrId,
              luPrId,
              data,
              schemaVersion,
              deleted,
              created,
              lastUpdated
            ) =>
          PostgresDocument[Id, Meta, Data](
            ex.to(id),
            meta
              .as[Meta]
              .fold(
                err =>
                  throw new Exception(
                    s"Failed to parse ${ttMeta.value} from $meta Error: ${err}"
                  ),
                v => v
              ),
            crPrId,
            luPrId,
            data
              .as[Data]
              .fold(
                err =>
                  throw new Exception(
                    s"Failed to parse ${tt.value} from $data Error: ${err}"
                  ),
                v => v
              ),
            schemaVersion,
            deleted,
            created,
            lastUpdated
          )
      }

  given writePostgresDocument[Id, Meta, Data](using
    ex: NewExtractor.Aux[Id, UUID],
    enMeta: JsonEncoder[Meta],
    en: JsonEncoder[Data]
  ): Write[PostgresDocument[Id, Meta, Data]] =
    Write[
      (
        UUID,
        Json,
        PrincipalId,
        PrincipalId,
        Json,
        Int,
        Boolean,
        OffsetDateTime,
        OffsetDateTime
      )
    ]
      .contramap {
        case PostgresDocument(
              id,
              meta,
              crPrId,
              luPrId,
              data,
              schemaVersion,
              deleted,
              created,
              lastUpdated
            ) =>
          (
            ex.from(id),
            meta.toJsonASTOrFail,
            crPrId,
            luPrId,
            data.toJsonASTOrFail,
            schemaVersion,
            deleted,
            created,
            lastUpdated
          )
      }
