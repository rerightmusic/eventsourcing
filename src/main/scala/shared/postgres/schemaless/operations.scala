package shared.postgres.schemaless

import cats.data.NonEmptyList
import cats.free.Free
import doobie.free.connection
import doobie.implicits.{toDoobieFoldableOps, toSqlInterpolator}
import doobie.util.Put
import doobie.ConnectionIO
import doobie.util.log.LogHandler
import doobie.util.update.Update
import doobie.{Fragment, Read, Write}
import shared.principals.PrincipalId
import shared.postgres.doobie.all.given
import shared.logging.all.*
import java.time.OffsetDateTime
import doobie.util.transactor.Transactor
import cats.Monad
import cats.effect.kernel.MonadCancelThrow
import cats.syntax.all.*
import java.util.logging.Logger
import doobie.util.log.{Success, ProcessingFailure, ExecFailure}
import shared.json.all.*

object operations:
  // TODO put in a service such that it has a Dev / Prod mode
  implicit val logHandler: LogHandler =
    LogHandler {
      case Success(s, a, e1, e2) =>
        ()
      // The following code comes in handy when needing to print SQL statements in Dev. Don't remove
      // if !s.contains("ON CONFLICT") || s.contains("aggregate_views") then ()
      // else
      //   logger.info(s"""Successful Statement Execution:
      //                   |
      //                   |  ${s.linesIterator
      //     .dropWhile(_.trim.isEmpty)
      //     .mkString("\n  ")}
      //                   |
      //                   | arguments = [${a.mkString(", ")}]
      //                   |   elapsed = ${e1.toMillis} ms exec + ${e2.toMillis} ms processing (${(e1 + e2).toMillis} ms total)
      // """.stripMargin)
      case ProcessingFailure(s, a, e1, e2, t) =>
        logger.error(s"""Failed Resultset Processing:
          |
          |  ${s.linesIterator.dropWhile(_.trim.isEmpty).mkString("\n  ")}
          |
          | arguments = [${a.mkString(", ")}]
          |   elapsed = ${e1.toMillis} ms exec + ${e2.toMillis} ms processing (failed) (${(e1 + e2).toMillis} ms total)
          |   failure = ${t.getMessage}
        """.stripMargin)

      case ExecFailure(s, a, e1, t) =>
        logger.error(s"""Failed Statement Execution:
          |
          |  ${s.linesIterator.dropWhile(_.trim.isEmpty).mkString("\n  ")}
          |
          | arguments = [${a.mkString(", ")}]
          |   elapsed = ${e1.toMillis} ms exec (failed)
          |   failure = ${t.getMessage}
        """.stripMargin)
    }

  val pgCols =
    List(
      "id",
      "meta",
      "created_by",
      "last_updated_by",
      "data",
      "schema_version",
      "deleted",
      "created",
      "last_updated"
    )
  val strPGDocStar =
    "id,meta,created_by,last_updated_by,data,schema_version,deleted,created,last_updated"

  val frPGDocStar = Fragment.const(strPGDocStar)
  val frPGDocStarPrefixed = (prefix: String) =>
    pgCols
      .map(c => Fragment.const(s"${prefix}${c}"))
      .foldSmash1(fr0"", fr0",", fr0"")

  def upsertInto[Id, Meta, Data](
    tableName: String,
    doc: PostgresDocument[Id, Meta, Data]
  )(implicit
    w: Write[PostgresDocument[Id, Meta, Data]]
  ): doobie.ConnectionIO[Int] =
    Update(
      s"INSERT INTO $tableName (${strPGDocStar}) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) "
        ++
          s"ON CONFLICT (id, schema_version) DO UPDATE SET last_updated_by = EXCLUDED.last_updated_by, data = EXCLUDED.data, last_updated = EXCLUDED.last_updated, deleted = EXCLUDED.deleted"
    )(w, logHandler).run(doc)

  def upsertInto[Id, Meta, Data](
    tableName: String,
    docs: NonEmptyList[PostgresDocument[Id, Meta, Data]]
  )(using
    w: Write[PostgresDocument[Id, Meta, Data]]
  ): doobie.ConnectionIO[Int] =
    Update(
      s"INSERT INTO $tableName (${strPGDocStar}) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) "
        ++
          s"ON CONFLICT (id, schema_version) DO UPDATE SET last_updated_by = EXCLUDED.last_updated_by, data = EXCLUDED.data, last_updated = EXCLUDED.last_updated, deleted = EXCLUDED.deleted"
    )(w, logHandler)
      .updateMany(docs)

  def insertInto[Id, Meta, Data](
    tableName: String,
    doc: PostgresDocument[Id, Meta, Data]
  )(using
    Write[PostgresDocument[Id, Meta, Data]]
  ): doobie.ConnectionIO[Int] =
    insertInto(tableName, NonEmptyList.one(doc))

  def insertInto[Id, Meta, Data](
    tableName: String,
    docs: NonEmptyList[PostgresDocument[Id, Meta, Data]]
  )(using
    w: Write[PostgresDocument[Id, Meta, Data]]
  ): doobie.ConnectionIO[Int] =
    Update(
      s"INSERT INTO $tableName (" + strPGDocStar + s") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)"
    )(w, logHandler).updateMany(docs)

  def insertInto[Id, Meta, Data](
    tableName: String,
    docs: List[PostgresDocument[Id, Meta, Data]]
  )(implicit
    writeDoc: Write[PostgresDocument[Id, Meta, Data]]
  ): doobie.ConnectionIO[Int] =
    docs match {
      case Nil    => Free.pure(0)
      case h :: t => insertInto(tableName, NonEmptyList(h, t))
    }

  def updateData[Id, Meta, Data](
    tableName: String,
    id: Id,
    principalId: PrincipalId,
    doc: Data
  )(using
    JsonEncoder[Data],
    Put[Id],
    Put[PrincipalId]
  ): doobie.ConnectionIO[Int] =
    (fr0"UPDATE ${Fragment.const(tableName)} set data = ${doc.toJsonASTOrFail}, last_updated_by = ${principalId}, " ++
      fr0"last_updated = ${OffsetDateTime.now} WHERE id = $id")
      .update(logHandler)
      .run

  def delete[Id, Meta, Data](
    tableName: String,
    id: Id,
    principalId: PrincipalId
  )(using Put[Id], Put[PrincipalId]): doobie.ConnectionIO[Int] =
    (fr0"UPDATE ${Fragment.const(tableName)} set deleted = true, " ++
      fr0"last_updated_by = ${principalId}, last_updated = ${OffsetDateTime.now} WHERE id = $id")
      .update(logHandler)
      .run

  def trySelectById[Id, Meta, Data](
    tableName: String,
    schemaVersion: Int,
    id: Id
  )(implicit
    idPut: Put[Id],
    read: Read[PostgresDocument[Id, Meta, Data]]
  ): Free[connection.ConnectionOp, Option[
    PostgresDocument[Id, Meta, Data]
  ]] =
    trySelectByIds(tableName, schemaVersion, NonEmptyList.one(id)).flatMap {
      case Nil     => connection.pure(None)
      case List(h) => connection.pure(Some(h))
      case List(_*) =>
        connection.raiseError[Option[PostgresDocument[Id, Meta, Data]]](
          new Exception(
            s"More than one result found for $tableName and $id"
          )
        )
    }

  def selectById[Id, Meta, Data](tableName: String, schemaVersion: Int, id: Id)(
    implicit
    idPut: Put[Id],
    read: Read[PostgresDocument[Id, Meta, Data]]
  ): Free[connection.ConnectionOp, PostgresDocument[Id, Meta, Data]] =
    selectByIds(tableName, schemaVersion, NonEmptyList.one(id)).flatMap {
      case NonEmptyList(head, Nil) => connection.pure(head)
      case NonEmptyList(_, _) =>
        connection.raiseError[PostgresDocument[Id, Meta, Data]](
          new Exception(s"More than one result found for $tableName and $id")
        )
    }

  def selectAll[Id, Meta, Data](tableName: String, schemaVersion: Int)(implicit
    read: Read[PostgresDocument[Id, Meta, Data]]
  ): Free[connection.ConnectionOp, List[
    PostgresDocument[Id, Meta, Data]
  ]] =
    Fragment
      .const(
        s"SELECT " + strPGDocStar + s" FROM $tableName where schema_version = ${schemaVersion} AND deleted = false order by last_updated DESC"
      )
      .query[PostgresDocument[Id, Meta, Data]]
      .to[List]

  def selectAllStream[Id, Meta, Data](tableName: String, schemaVersion: Int)(
    implicit read: Read[PostgresDocument[Id, Meta, Data]]
  ): fs2.Stream[ConnectionIO, PostgresDocument[
    Id,
    Meta,
    Data
  ]] =
    Fragment
      .const(
        s"SELECT " + strPGDocStar + s" FROM $tableName where schema_version = ${schemaVersion} AND deleted = false order by last_updated DESC"
      )
      .query[PostgresDocument[Id, Meta, Data]]
      .stream

  def selectByIds[Id, Meta, Data](
    tableName: String,
    schemaVersion: Int,
    ids: NonEmptyList[Id]
  )(implicit
    idPut: Put[Id],
    read: Read[PostgresDocument[Id, Meta, Data]]
  ): Free[connection.ConnectionOp, NonEmptyList[
    PostgresDocument[Id, Meta, Data]
  ]] =
    trySelectByIds(tableName, schemaVersion, ids)
      .flatMap {
        case h :: t => connection.pure(NonEmptyList(h, t))
        case Nil =>
          connection
            .raiseError[NonEmptyList[PostgresDocument[Id, Meta, Data]]](
              new Exception(
                s"Empty results for $tableName and $ids"
              )
            )
      }

  // ORDINALITY PRESERVES ORDERING
  def trySelectByIds[Id, Meta, Data](
    tableName: String,
    schemaVersion: Int,
    ids: NonEmptyList[Id]
  )(implicit
    idPut: Put[Id],
    read: Read[PostgresDocument[Id, Meta, Data]]
  ): Free[connection.ConnectionOp, List[
    PostgresDocument[Id, Meta, Data]
  ]] =
    (fr0"SELECT ${frPGDocStarPrefixed("t.")} FROM ${Fragment.const(tableName)} as t JOIN" ++ ids
      .map(x => fr0"$x")
      .foldSmash1(
        fr0" UNNEST(ARRAY[",
        fr0",",
        fr0"]) "
      ) ++ fr0"""WITH ORDINALITY AS ids(id, ord) ON ids.id = t.id WHERE schema_version = ${schemaVersion} AND 
      schema_version = ${schemaVersion} AND deleted = false ORDER BY ord""")
      .query[PostgresDocument[Id, Meta, Data]]
      .to[List]

  def select[Id, Meta, Data](tableName: String, schemaVersion: Int)(using
    read: Read[PostgresDocument[Id, Meta, Data]]
  ) =
    new Select[Id, Meta, Data](tableName, schemaVersion, None, None, None, None)

  def selectUpdated[Id, Meta, Data](
    tableName: String,
    schemaVersion: Int,
    from: Option[OffsetDateTime],
    to: Option[OffsetDateTime]
  )(implicit
    read: Read[PostgresDocument[Id, Meta, Data]]
  ): Free[connection.ConnectionOp, List[
    PostgresDocument[Id, Meta, Data]
  ]] =
    (fr0"""SELECT ${frPGDocStar} FROM ${Fragment
      .const(tableName)} WHERE ${from
      .map(f => fr0"last_updated >= ${f}")
      .getOrElse(fr0"true")} AND 
      ${to
      .map(t =>
        fr0"""last_updated < ${t} AND deleted = false AND schema_version = ${schemaVersion}"""
      )
      .getOrElse(fr0"true")}""")
      .query[PostgresDocument[Id, Meta, Data]]
      .to[List]

  class Select[Id, Meta, Data](
    val tableName: String,
    val schemaVersion: Int,
    val filter: Option[Fragment],
    val orderBy: Option[Fragment],
    val from: Option[Int],
    val to: Option[Int]
  )(using read: Read[PostgresDocument[Id, Meta, Data]]):
    def where(filter: Filter | FilterPredicate) =
      new Select(
        tableName,
        schemaVersion,
        Some(filter match
          case Filter(f)          => f
          case FilterPredicate(f) => f
        ),
        orderBy,
        from,
        to
      )

    def orderBy(fragment: Fragment) =
      new Select(tableName, schemaVersion, filter, Some(fragment), from, to)

    def limit(from: Option[Int], to: Option[Int]) =
      new Select(tableName, schemaVersion, filter, orderBy, from, to)

    def transact[M[_]: MonadCancelThrow](
      txn: Transactor[M]
    ) =
      import _root_.doobie.implicits.*
      query.transact(txn)

    def transactWithCount[M[_]: MonadCancelThrow](
      txn: Transactor[M]
    ) =
      import _root_.doobie.implicits.*
      queryWithCount.transact(txn)

    private def _query(count: Boolean = false) =
      val orderLimit =
        fr"${orderBy
          .fold(fr0"")(o => fr0" ORDER BY ${o}")} ${from.fold(fr0"")(f =>
          fr0"OFFSET ${f}"
        )} ${to.fold(fr0"")(t => fr0"LIMIT ${t - from.getOrElse(0)}")}"

      if count then
        fr0"""
        WITH cte AS (
          SELECT ${frPGDocStar}
          FROM ${Fragment.const(tableName)}
          WHERE schema_version = ${schemaVersion} AND deleted = false ${filter
          .fold(fr0"")(x => fr0" AND ${x}")}
        )
        SELECT *
        FROM  (
          TABLE cte
          ${orderLimit}
        ) sub
        JOIN (SELECT count(*) FROM cte) c(full_count) ON true;
      """
      else
        fr0"SELECT ${frPGDocStar} FROM ${Fragment.const(tableName)} where schema_version = ${schemaVersion} AND deleted = false ${filter
          .fold(fr0"")(x => fr0" AND ${x}")}${orderLimit}"

    def query =
      _query()
        .query[PostgresDocument[Id, Meta, Data]]
        .to[List]

    def queryWithCount = _query(true)
      .query[(PostgresDocument[Id, Meta, Data], Int)]
      .to[List]
      .map(l =>
        val count = l.headOption.fold(0)(_._2)
        l.map(_._1) -> count
      )

    def stream =
      _query()
        .query[PostgresDocument[Id, Meta, Data]]
        .stream

  case class Filter(fragment: Fragment)
  object Filter:
    def not(f: Fragment) = Filter(fr0"NOT ${f}")
    def noop = Filter(fr0"true")
    def _false = Filter(fr0"false")
    def textFieldEquals(
      getField: String,
      value: String
    ) = Filter(
      fr0"""(${Fragment.const(getField)})::text = ${value}"""
    )

    def iTextFieldEquals(
      getField: String,
      value: String
    ) = Filter(
      fr0"""lower((${Fragment.const(getField)})::text) = ${value.toLowerCase}"""
    )

    def textFieldInArray(
      getField: String,
      values: List[String]
    ) = values.toNel
      .map(_ =>
        Filter(fr0"""(${Fragment.const(getField)})::text in ${values
          .map(n => fr0"${n}")
          .foldSmash1(fr0"(", fr0",", fr0")")}""")
      )
      .noop

    def jsonArrayContainsValue(
      getField: String,
      jsonMatch: String | (String, String)
    ) = Filter(
      jsonMatch match
        case s: String =>
          fr0"""(${Fragment.const(getField)})::jsonb @> ${arrayToJson(
            List(fr0"${s}")
          )}"""
        case s: (String, String) =>
          val obj = Map(s._1 -> s._2).toJsonPretty
          fr0"""(${Fragment.const(getField)})::jsonb @> ${arrayToJson(
            List(fr0"${Map(s._1 -> s._2).toJsonASTOrFail}")
          )}"""
    )

    private def arrayToJson(list: List[Fragment]) = list
      .foldSmash1(fr0"array_to_json(ARRAY[", fr0",", fr0"])::jsonb")

    def jsonValueInArray(
      getField: String,
      values: List[String]
    ) = values.toNel
      .map(_ =>
        Filter(fr0"""(${Fragment.const(getField)})::text = ANY (${values})""")
      )
      .noop

    def jsonArrayContainsAnyValue(
      getObjectArray: String,
      values: List[String]
    ) =
      values.toNel
        .map(_ =>
      // format: off
      Filter(
        fr0"""exists (select * from jsonb_array_elements(${Fragment.const(getObjectArray)}) as r(jdoc) where
        jdoc ??| ${values})"""
      )).noop
      // format: on

    def jsonObjectArrayContainsAnyValue(
      getObjectArray: String,
      getObjectField: String => String,
      values: List[String]
    ) =
      values.toNel
        .map(_ =>
      // format: off
      Filter(
        fr0"""exists (select * from jsonb_array_elements(${Fragment.const(getObjectArray)}) as r(jdoc) where
        ${Fragment.const(getObjectField("jdoc"))} ??| ${values})"""
      )).noop
      // format: on

    def jsonObjectArrayN2ContainsAnyValue(
      getObjectArray1: String,
      getObjectArray2: String => String,
      getObjectField: String => String,
      values: List[String],
      arr1Nullable: Boolean = false
    ) =
      val query =
        fr"""exists (select * from jsonb_array_elements(${Fragment.const(
          getObjectArray1
        )}) as r(jdoc) where exists
          (select * from jsonb_array_elements(${Fragment.const(
          getObjectArray2("jdoc")
        )}) as r(jdoc2) where
          ${Fragment.const(getObjectField("jdoc2"))} ??| ${values}))"""
      values.toNel
        .map(_ =>
      // format: off
      Filter(
        if arr1Nullable then 
          fr0"""
          case ${Fragment.const(getObjectArray1)}
	        when 'null' then false
          else ${query}
          end"""
        else query
      )).noop
      // format: on

    def jsonObjectArrayIEquals(
      getObjectArray: String,
      getObjectField: String => String,
      value: String
    ) =
      // format: off
      Filter(
        fr0"""exists (select * from jsonb_array_elements(${Fragment.const(getObjectArray)}) as r(jdoc) where
        lower((${Fragment.const(getObjectField("jdoc"))})::text) = ${value.toLowerCase})"""
      )
      // format: on

    def jsonObjectArrayILike(
      getObjectArray: String,
      getObjectField: String => String,
      likeMatch: String
    ) =
      // format: off
      Filter(
        fr0"""exists (select * from jsonb_array_elements(${Fragment.const(getObjectArray)}) as r(jdoc) where
        ${Fragment.const(getObjectField("jdoc"))} ILIKE ${likeMatch})"""
      )
      // format: on

    def jsonObjectArrayILike2(
      getObjectArray: String,
      getObjectField1: String => String,
      likeMatch1: String,
      getObjectField2: String => String,
      likeMatch2: String
    ) =
      // format: off
      Filter(
        fr0"""exists (select * from jsonb_array_elements(${Fragment.const(getObjectArray)}) as r(jdoc) where
        ${Fragment.const(getObjectField1("jdoc"))} ILIKE ${likeMatch1} AND ${Fragment.const(getObjectField2("jdoc"))} ILIKE ${likeMatch2})"""
      )
      // format: on

    def iLike(
      getField: String,
      likeMatch: String
    ) =
      // format: off
      Filter(fr0"""${Fragment.const(getField)} ILIKE ${likeMatch}""")
      // format: on
  extension (v: Option[Filter])
    def noop = v.getOrElse(Filter.noop)
    def orFalse = v.getOrElse(Filter._false)
  extension (v: Option[FilterPredicate])
    def noop = v.getOrElse(FilterPredicate(Filter.noop.fragment))
    def orFalse = v.getOrElse(FilterPredicate(Filter._false.fragment))
  given Conversion[Filter, FilterPredicate] with
    def apply(v: Filter) = FilterPredicate(v.fragment)
  case class FilterPredicate(fragment: Fragment):
    def or(r: FilterPredicate) =
      FilterPredicate(
        List(fragment, r.fragment).foldSmash1(fr0"(", fr0" OR ", fr0")")
      )
    def and(r: FilterPredicate) =
      FilterPredicate(
        List(fragment, r.fragment).foldSmash1(fr0"(", fr0" AND ", fr0")")
      )
    def not(r: FilterPredicate) = fr0"NOT ${r.fragment}"
