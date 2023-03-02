package eventsourcing.infra

import cats.data.NonEmptyList
import cats.free.Free
import _root_.doobie.implicits.{toDoobieFoldableOps, toSqlInterpolator}
import _root_.doobie.util.update.Update
import _root_.doobie.{Fragment, Write}
import doobie.*
import doobie.implicits.*
import shared.postgres.schemaless.operations.logHandler

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
