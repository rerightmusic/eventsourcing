package eventsourcing.infra

import cats.data.NonEmptyList
import cats.free.Free
import _root_.doobie.free.connection
import _root_.doobie.implicits.{toDoobieFoldableOps, toSqlInterpolator}
import _root_.doobie.util.Put
import _root_.doobie.util.log.LogHandler
import _root_.doobie.util.update.Update
import _root_.doobie.{Fragment, Read, Write}
import _root_.doobie.postgres.implicits.*
import shared.principals.PrincipalId
import java.time.OffsetDateTime
import doobie.util.transactor.Transactor
import cats.Monad
import cats.effect.kernel.MonadCancelThrow
import cats.effect.IO
import org.postgresql.PGNotification
import zio.Task
import doobie.Transactor
import zio.Duration
import doobie.*
import doobie.implicits.*
import doobie.postgres.*
import cats.syntax.all.*
import zio.interop.catz.*
import shared.postgres.doobie.WithTransactor
import shared.postgres.schemaless.operations.logHandler
import fs2.{Stream, Pipe}
import fs2.Stream.*
import scala.concurrent.duration.*
import cats.effect.kernel.Resource
import zio.interop.catz.implicits.*
import eventsourcing.domain.types.SequenceId
import zio.RIO
import zio.ZIO
import zio.managed.*

object postgresOperations:
  case class Cols(cols: List[String]):
    val strPGDocStar = cols.mkString(",")
    val frPGDocStar = Fragment.const(strPGDocStar)
    val frPGDocStarPrefixed = (prefix: String) =>
      cols
        .map(c => Fragment.const(s"${prefix}${c}"))
        .foldSmash1(fr0"", fr0",", fr0"")

  val readCols = Cols(
    List(
      "sequence_id",
      "id",
      "meta",
      "created_by",
      "created",
      "version",
      "schema_version",
      "deleted",
      "data"
    )
  )

  val writePGCols =
    Cols(readCols.cols.filterNot(_ == "sequence_id"))

  def insertInto[Id, Meta, Data](
    tableName: String,
    doc: WriteEventPostgresDocument[Id, Meta, Data]
  )(using
    Write[WriteEventPostgresDocument[Id, Meta, Data]]
  ): doobie.ConnectionIO[Int] =
    insertInto(tableName, NonEmptyList.one(doc))

  def insertInto[Id, Meta, Data](
    tableName: String,
    docs: NonEmptyList[WriteEventPostgresDocument[Id, Meta, Data]]
  )(using
    w: Write[WriteEventPostgresDocument[Id, Meta, Data]]
  ): doobie.ConnectionIO[Int] =
    Update(
      s"INSERT INTO $tableName (${writePGCols.strPGDocStar}) VALUES (?, ?, ?, ?, ?, ?, ?, ?)"
    )(w, logHandler).updateMany(docs)

  def insertInto[Id, Meta, Data](
    tableName: String,
    docs: List[WriteEventPostgresDocument[Id, Meta, Data]]
  )(implicit
    writeDoc: Write[WriteEventPostgresDocument[Id, Meta, Data]]
  ): doobie.ConnectionIO[Int] =
    docs match {
      case Nil    => Free.pure(0)
      case h :: t => insertInto(tableName, NonEmptyList(h, t))
    }
