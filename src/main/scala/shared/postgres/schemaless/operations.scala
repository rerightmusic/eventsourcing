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
    val jdkLogger = Logger.getLogger(getClass.getName)
    LogHandler {
      case Success(s, a, e1, e2) =>
        ()
      // if s.contains("aggregate_views") then ()
      // else
      //   jdkLogger.info(s"""Successful Statement Execution:
      //                   |
      //                   |  ${s.linesIterator
      //     .dropWhile(_.trim.isEmpty)
      //     .mkString("\n  ")}
      //                   |
      //                   | arguments = [${a.mkString(", ")}]
      //                   |   elapsed = ${e1.toMillis} ms exec + ${e2.toMillis} ms processing (${(e1 + e2).toMillis} ms total)
      // """.stripMargin)
      case ProcessingFailure(s, a, e1, e2, t) =>
        jdkLogger.severe(s"""Failed Resultset Processing:
          |
          |  ${s.linesIterator.dropWhile(_.trim.isEmpty).mkString("\n  ")}
          |
          | arguments = [${a.mkString(", ")}]
          |   elapsed = ${e1.toMillis} ms exec + ${e2.toMillis} ms processing (failed) (${(e1 + e2).toMillis} ms total)
          |   failure = ${t.getMessage}
        """.stripMargin)

      case ExecFailure(s, a, e1, t) =>
        jdkLogger.severe(s"""Failed Statement Execution:
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
      "deleted",
      "created",
      "last_updated"
    )
  val strPGDocStar =
    "id,meta,created_by,last_updated_by,data,deleted,created,last_updated"

  val frPGDocStar = Fragment.const(strPGDocStar)
  val frPGDocStarPrefixed = (prefix: String) =>
    pgCols
      .map(c => Fragment.const(s"${prefix}${c}"))
      .foldSmash1(fr0"", fr0",", fr0"")

  def upsertInto[Id, Meta, Data](
    tableName: String,
    doc: PostgresDocument[Id, Meta, Data]
  )(implicit
    writeDoc: Write[PostgresDocument[Id, Meta, Data]]
  ): doobie.ConnectionIO[Int] = upsertInto(tableName, NonEmptyList.one(doc))

  def upsertInto[Id, Meta, Data](
    tableName: String,
    docs: NonEmptyList[PostgresDocument[Id, Meta, Data]]
  )(using
    Write[PostgresDocument[Id, Meta, Data]]
  ): doobie.ConnectionIO[Int] =
    Update(
      s"INSERT INTO $tableName (${strPGDocStar}) VALUES (?, ?, ?, ?, ?, ?, ?, ?) "
        ++
          s"ON CONFLICT (id) DO UPDATE SET last_updated_by = EXCLUDED.last_updated_by, data = EXCLUDED.data, last_updated = EXCLUDED.last_updated, deleted = EXCLUDED.deleted"
    )(implicitly, logHandler).updateMany(docs)

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
      s"INSERT INTO $tableName (" + strPGDocStar + s") VALUES (?, ?, ?, ?, ?, ?, ?, ?)"
    ).updateMany(docs)

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

  def trySelectById[Id, Meta, Data](tableName: String, id: Id)(implicit
    idPut: Put[Id],
    read: Read[PostgresDocument[Id, Meta, Data]]
  ): Free[connection.ConnectionOp, Option[
    PostgresDocument[Id, Meta, Data]
  ]] =
    trySelectByIds(tableName, NonEmptyList.one(id)).flatMap {
      case Nil     => connection.pure(None)
      case List(h) => connection.pure(Some(h))
      case List(_*) =>
        connection.raiseError[Option[PostgresDocument[Id, Meta, Data]]](
          new Exception(
            s"More than one result found for $tableName and $id"
          )
        )
    }

  def selectById[Id, Meta, Data](tableName: String, id: Id)(implicit
    idPut: Put[Id],
    read: Read[PostgresDocument[Id, Meta, Data]]
  ): Free[connection.ConnectionOp, PostgresDocument[Id, Meta, Data]] =
    selectByIds(tableName, NonEmptyList.one(id)).flatMap {
      case NonEmptyList(head, Nil) => connection.pure(head)
      case NonEmptyList(_, _) =>
        connection.raiseError[PostgresDocument[Id, Meta, Data]](
          new Exception(s"More than one result found for $tableName and $id")
        )
    }

  def selectAll[Id, Meta, Data](tableName: String)(implicit
    read: Read[PostgresDocument[Id, Meta, Data]]
  ): Free[connection.ConnectionOp, List[
    PostgresDocument[Id, Meta, Data]
  ]] =
    Fragment
      .const(
        s"SELECT " + strPGDocStar + s" FROM $tableName where deleted = false order by last_updated DESC"
      )
      .query[PostgresDocument[Id, Meta, Data]]
      .to[List]

  def selectAllStream[Id, Meta, Data](tableName: String)(implicit
    read: Read[PostgresDocument[Id, Meta, Data]]
  ): fs2.Stream[ConnectionIO, PostgresDocument[
    Id,
    Meta,
    Data
  ]] =
    Fragment
      .const(
        s"SELECT " + strPGDocStar + s" FROM $tableName where deleted = false order by last_updated DESC"
      )
      .query[PostgresDocument[Id, Meta, Data]]
      .stream

  def selectByIds[Id, Meta, Data](tableName: String, ids: NonEmptyList[Id])(
    implicit
    idPut: Put[Id],
    read: Read[PostgresDocument[Id, Meta, Data]]
  ): Free[connection.ConnectionOp, NonEmptyList[
    PostgresDocument[Id, Meta, Data]
  ]] =
    trySelectByIds(tableName, ids)
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
  def trySelectByIds[Id, Meta, Data](tableName: String, ids: NonEmptyList[Id])(
    implicit
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
      ) ++ fr0"WITH ORDINALITY AS ids(id, ord) ON ids.id = t.id WHERE deleted = false ORDER BY ord")
      .query[PostgresDocument[Id, Meta, Data]]
      .to[List]

  def select[Id, Meta, Data](tableName: String)(using
    read: Read[PostgresDocument[Id, Meta, Data]]
  ) =
    new Select[Id, Meta, Data](tableName, None, None, None, None)

  def selectUpdated[Id, Meta, Data](
    tableName: String,
    from: Option[OffsetDateTime],
    to: Option[OffsetDateTime]
  )(implicit
    read: Read[PostgresDocument[Id, Meta, Data]]
  ): Free[connection.ConnectionOp, List[
    PostgresDocument[Id, Meta, Data]
  ]] =
    (fr0"SELECT ${frPGDocStar} FROM ${Fragment
      .const(tableName)} WHERE ${from
      .map(f => fr0"last_updated >= ${f}")
      .getOrElse(fr0"true")} AND ${to.map(t => fr0"last_updated < ${t}").getOrElse(fr0"true")}")
      .query[PostgresDocument[Id, Meta, Data]]
      .to[List]

  class Select[Id, Meta, Data](
    val tableName: String,
    val filter: Option[Fragment],
    val orderBy: Option[Fragment],
    val from: Option[Int],
    val to: Option[Int]
  )(using read: Read[PostgresDocument[Id, Meta, Data]]):
    def where(filter: Filter | FilterPredicate) =
      new Select(
        tableName,
        Some(filter match
          case Filter(f)          => f
          case FilterPredicate(f) => f
        ),
        orderBy,
        from,
        to
      )

    def orderBy(fragment: Fragment) =
      new Select(tableName, filter, Some(fragment), from, to)

    def limit(from: Option[Int], to: Option[Int]) =
      new Select(tableName, filter, orderBy, from, to)

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

      if count then fr0"""
        WITH cte AS (
          SELECT ${frPGDocStar}
          FROM ${Fragment.const(tableName)}
          WHERE deleted = false ${filter.fold(fr0"")(x => fr0" AND ${x}")}
        )
        SELECT *
        FROM  (
          TABLE cte
          ${orderLimit}
        ) sub
        JOIN (SELECT count(*) FROM cte) c(full_count) ON true;
      """
      else
        fr0"SELECT ${frPGDocStar} FROM ${Fragment.const(tableName)} where deleted = false ${filter
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
