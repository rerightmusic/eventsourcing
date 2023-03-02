package eventsourcing.domain

import cats.data.NonEmptyList
import types.*
import zio.ZIO
import zio.interop.catz.*
import cats.syntax.all.*
import shared.principals.PrincipalId
import shared.newtypes.NewExtractor
import shared.uuid.all.*
import shared.time.all.*
import shared.error.all.*
import fs2.*
import org.postgresql.util.PSQLException
import zio.Schedule
import zio.*

import shared.error.all
object operations:
  def getAggregate[Agg, Id, Meta, EventData, Command](using
    agg: Aggregate.Aux[Agg, Id, Meta, EventData, Command],
    tId: zio.Tag[Id],
    tMeta: zio.Tag[Meta],
    tEventData: zio.Tag[EventData],
    tSchemaless: zio.Tag[Schemaless[agg.Id, agg.Meta, Agg]],
    tAgg: zio.Tag[Agg]
  )(
    id: Id
  ): ZIO[
    AggregateStore[Id, Meta, EventData] &
      AggregateViewStore.Schemaless[Id, Meta, Agg, Id, Agg *: EmptyTuple],
    Throwable,
    Agg
  ] = for
    view <- getAggregateView[Map[Id, Schemaless[Id, Meta, Agg]], NonEmptyList[
      Id
    ], Agg *: EmptyTuple](
      Some(NonEmptyList.of(id))
    ).mapError {
      case _: AggregateViewError.AggregateViewMissing[?] =>
        AggregateError.AggregateMissing(
          id,
          agg.versionedStoreName
        )
      case err => err
    }
    agg <- view.flatMap(_.get(id)) match
      case None =>
        ZIO
          .fail[AggregateError](
            AggregateError.AggregateMissing(
              id,
              agg.versionedStoreName
            )
          )
      case Some(a) => ZIO.succeed(a.data)
  yield agg

  def exists[Agg, Id, Meta, EventData, Command](using
    agg: Aggregate.Aux[Agg, Id, Meta, EventData, Command],
    tId: zio.Tag[Id],
    tMeta: zio.Tag[Meta],
    tEventData: zio.Tag[EventData],
    tSchemaless: zio.Tag[Schemaless[agg.Id, agg.Meta, Agg]],
    tAgg: zio.Tag[Agg]
  )(
    id: Id
  ): ZIO[
    AggregateStore[Id, Meta, EventData] &
      AggregateViewStore.Schemaless[Id, Meta, Agg, Id, Agg *: EmptyTuple],
    Throwable,
    Boolean
  ] = for
    view <- getAggregateView[Map[Id, Schemaless[Id, Meta, Agg]], NonEmptyList[
      Id
    ], Agg *: EmptyTuple](
      Some(NonEmptyList.of(id))
    ).mapError {
      case _: AggregateViewError.AggregateViewMissing[?] =>
        AggregateError.AggregateMissing(
          id,
          agg.versionedStoreName
        )
      case err => err
    }
    agg <- view.flatMap(_.get(id)) match
      case None =>
        ZIO.succeed(false)
      case Some(_) => ZIO.succeed(true)
  yield agg

  def runCreateCommand[Agg](using
    agg: Aggregate[Agg],
    ex: NewExtractor.Aux[agg.Id, UUID],
    tId: zio.Tag[agg.Id],
    tMeta: zio.Tag[agg.Meta],
    tEventData: zio.Tag[agg.EventData]
  )(
    meta: agg.Meta,
    createdBy: PrincipalId,
    cmd: agg.Command
  ): ZIO[
    AggregateStore[agg.Id, agg.Meta, agg.EventData],
    Throwable,
    agg.Id
  ] =
    for
      id <- generateUUID.map(ex.to)
      _ <- runCommand[
        Agg,
        agg.Id,
        agg.Meta,
        agg.EventData,
        agg.Command,
        agg.CommandError
      ](
        None,
        id,
        meta,
        createdBy,
        cmd
      )
    yield id

  def runUpdateCommand[Agg](using
    agg: Aggregate[Agg],
    tId: zio.Tag[agg.Id],
    tMeta: zio.Tag[agg.Meta],
    tEventData: zio.Tag[agg.EventData],
    tSchemaless: zio.Tag[Schemaless[agg.Id, agg.Meta, Agg]],
    tAgg: zio.Tag[Agg]
  )(
    id: agg.Id,
    meta: agg.Meta,
    createdBy: PrincipalId,
    cmd: agg.Command
  ): ZIO[
    AggregateStore[agg.Id, agg.Meta, agg.EventData] &
      AggregateViewStore.Schemaless[
        agg.Id,
        agg.Meta,
        Agg,
        agg.Id,
        Agg *: EmptyTuple
      ],
    Throwable,
    Unit
  ] = for
    aggr <- getAggregate[Agg, agg.Id, agg.Meta, agg.EventData, agg.Command](id)
    _ <- runCommand[
      Agg,
      agg.Id,
      agg.Meta,
      agg.EventData,
      agg.Command,
      agg.CommandError
    ](
      Some(aggr),
      id,
      meta,
      createdBy,
      cmd
    )
  yield ()

  def runExecuteCommand[Agg](using
    agg: Aggregate[Agg],
    tId: zio.Tag[agg.Id],
    tMeta: zio.Tag[agg.Meta],
    tEventData: zio.Tag[agg.EventData],
    tSchemaless: zio.Tag[Schemaless[agg.Id, agg.Meta, Agg]],
    tAgg: zio.Tag[Agg],
    tStore: zio.Tag[AggregateViewStore.Schemaless[
      agg.Id,
      agg.Meta,
      Agg,
      agg.Id,
      Agg *: EmptyTuple
    ]]
  )(
    id: agg.Id,
    meta: agg.Meta,
    createdBy: PrincipalId,
    cmd: agg.Command
  ): ZIO[
    AggregateStore[agg.Id, agg.Meta, agg.EventData] &
      AggregateViewStore.Schemaless[
        agg.Id,
        agg.Meta,
        Agg,
        agg.Id,
        Agg *: EmptyTuple
      ],
    agg.CommandError | Throwable,
    Unit
  ] = for
    aggr <- getAggregateView[Map[
      agg.Id,
      Schemaless[agg.Id, agg.Meta, Agg]
    ], NonEmptyList[
      agg.Id
    ], Agg *: EmptyTuple](Some(NonEmptyList.of(id)))
    _ <- runCommand[
      Agg,
      agg.Id,
      agg.Meta,
      agg.EventData,
      agg.Command,
      agg.CommandError
    ](
      aggr.flatMap(_.headOption.map(_._2.data)),
      id,
      meta,
      createdBy,
      cmd
    )
  yield ()

  private def runCommand[Agg, Id, Meta, EventData, Command, CommandError](
    aggr: Option[Agg],
    id: Id,
    meta: Meta,
    createdBy: PrincipalId,
    cmd: Command
  )(using
    agg: Aggregate.Aux[Agg, Id, Meta, EventData, Command],
    tId: zio.Tag[Id],
    tMeta: zio.Tag[Meta],
    tEventData: zio.Tag[EventData]
  ): ZIO[AggregateStore[Id, Meta, EventData], CommandError | Throwable, Unit] =
    for
      evs <- ZIO.fromEither(agg.handleCommand(aggr, cmd))
      _ <- ZIO.environmentWithZIO[AggregateStore[Id, Meta, EventData]](
        _.get.persistEventsForId(id, meta, createdBy, evs)
      )
    yield ()

  def runAggregateView[View](using
    aggView: AggregateViewClass[View],
    tStore: zio.Tag[
      AggregateViewStore[aggView.ActualView, aggView.Query, aggView.Aggregates]
    ]
  )(
    mode: AggregateViewMode,
    subscribe: Boolean
  ): ZIO[
    AggregateViewStore[aggView.ActualView, aggView.Query, aggView.Aggregates],
    Throwable,
    Unit
  ] = runAggregateView_(mode, subscribe)
    .tapErrorCause(e =>
      for
        _ <- logErrorCauseSquashed(
          s"Error with view ${aggView.instance.versionedStoreName}",
          e
        )
        store <- ZIO
          .service[
            AggregateViewStore[
              aggView.ActualView,
              aggView.Query,
              aggView.Aggregates
            ]
          ]
        _ <- store.mergeAggregateViewStatus(
          AggregateViewStatus(
            sequenceIds = Map(),
            error = Some(e.prettyPrint)
          )
        )
      yield ()
    )
    .retry(
      (Schedule.spaced(1.second) >>> Schedule.elapsed)
        .whileOutput(_ < 1.minute) && Schedule
        .recurWhile {
          case _: PSQLException => true
          case _                => false
        }
    )

  private def runAggregateView_[View](using
    aggView: AggregateViewClass[View],
    tStore: zio.Tag[
      AggregateViewStore[aggView.ActualView, aggView.Query, aggView.Aggregates]
    ]
  )(
    mode: AggregateViewMode,
    subscribe: Boolean
  ): ZIO[
    AggregateViewStore[aggView.ActualView, aggView.Query, aggView.Aggregates],
    Throwable,
    Unit
  ] = for
    _ <- ZIO.logInfo(s"${aggView.instance.versionedStoreName} started")
    catchUpStartTime <- now
    store <- ZIO
      .environment[
        AggregateViewStore[
          aggView.ActualView,
          aggView.Query,
          aggView.Aggregates
        ]
      ]
    status <- mode match
      case AggregateViewMode.Continue =>
        for
          st <- store.get.readAggregateViewStatus
          res <- st match
            case None => store.get.resetAggregateView.map(_ => None)
            case Some(st_) if st_.sequenceIds.isEmpty =>
              store.get.resetAggregateView.map(_ => None)
            case Some(st_) => ZIO.some(st_)
        yield res
      case AggregateViewMode.Restart =>
        store.get.resetAggregateView
          .map(_ => None)

    from = status.map(_.next)
    _ <- (store.get
      .streamEventsFrom(None, from)
      .map(AggregateViewStage.CatchUp -> _)
      ++ (if subscribe then store.get.subscribeEventStream
          else Stream.empty)
        .map(c =>
          val removeDuplicates = c.foldLeft(
            fs2.Chunk.empty[AggregateViewEvent[aggView.Aggregates]] -> 0
          )((st, n) =>
            if n.event.sequenceId.value <= st._2 then st
            else (st._1 ++ fs2.Chunk(n), n.event.sequenceId.value)
          )
          AggregateViewStage.Sync -> removeDuplicates._1
        ))
      .through(s =>
        s.evalMapFilter((stage, evs) =>
          val isCatchUp = stage == AggregateViewStage.CatchUp
          evs.toNel.fold(ZIO.none)(evs_ =>
            for
              startTime <- now
              res <- store.get.readAggregateViewAndStatus(
                Some(aggView.instance.getQuery(evs_))
              )
              (state, status) = res
              filtered = evs_.filterNot(ev =>
                ev.event.sequenceId.value <= status.fold(0)(x =>
                  x.getSequenceId(ev.name).value
                )
              )

              res <- filtered.toNel.fold(ZIO.none)(filtered_ =>
                for
                  _ <-
                    ZIO.logInfo(
                      s"${aggView.instance.versionedStoreName} ${if isCatchUp then "CatchUp"
                      else "Sync"} running on ${filtered_.length} events, last sequenceId: ${filtered_.last.event.sequenceId}"
                    )
                  res = filtered_.foldLeft(
                    Either
                      .right[AggregateViewError, Option[aggView.ActualView]](
                        state
                      )
                  )((prev, ev) =>
                    for
                      prev_ <- prev
                      res <- aggView.instance.aggregate(prev_, ev)
                    yield Some(res)
                  )
                  res_ <- res match
                    case Left(err) =>
                      for
                        _ <- store.get.mergeAggregateViewStatus(
                          AggregateViewStatus(
                            sequenceIds = Map(),
                            error = Some(err.msg)
                          )
                        )
                        r <- ZIO.fail(err)
                      yield r
                    case Right(None) =>
                      val head = filtered_.head
                      val last = filtered_.last
                      val err =
                        s"Failed to aggregate Start event: ${head.name -> head.event.sequenceId} " +
                          s"End event: ${last.name -> last.event.sequenceId}"
                      for
                        _ <- store.get.mergeAggregateViewStatus(
                          AggregateViewStatus(
                            sequenceIds = Map(),
                            error = Some(err)
                          )
                        )
                        r <- ZIO.fail[AggregateViewError](
                          AggregateViewError.RunAggregateViewError(
                            err,
                            aggView.instance.versionedStoreName
                          )
                        )
                      yield r
                    case Right(Some(a)) => ZIO.succeed(a)
                  endTime <- now
                yield Some(
                  (
                    status,
                    AggregateViewStatus
                      .fromEvents(
                        filtered_,
                        catchupDuration =
                          if isCatchUp then
                            Some(differenceMillis(catchUpStartTime, endTime))
                          else None,
                        catchupEventsSize =
                          if isCatchUp then
                            status
                              .flatMap(_.catchupEventsSize)
                              .fold(Some(evs_.size))(x => Some(x + evs_.size))
                          else None,
                        syncDuration =
                          if !isCatchUp then
                            Some(differenceMillis(startTime, endTime))
                          else None,
                        syncEventsSize =
                          if !isCatchUp then Some(evs_.size)
                          else None
                      ),
                    res_
                  )
                )
              )
            yield (res.map(_ -> isCatchUp))
          )
        )
      )
      .through(s =>
        s.evalMap((x, isCatchUp) =>
          for
            _ <- store.get.persistAggregateView(
              x._1,
              x._2,
              x._3
            )
            _ <- ZIO.logInfo(
              s"${aggView.instance.versionedStoreName} ${if isCatchUp then "CatchUp"
              else "Sync"} ran ${x._2.sequenceIds}"
            )
          yield ()
        )
      )
      .compile
      .drain
  yield ()

  def getAggregateView[View, Query, Aggregates <: NonEmptyTuple](using
    view: AggregateView.Aux[View, Query, Aggregates],
    tView: zio.Tag[View],
    tQuery: zio.Tag[Query],
    tAggregates: zio.Tag[Aggregates],
    tStore: zio.Tag[AggregateViewStore[View, Query, Aggregates]]
  )(
    query: Option[Query]
  ): ZIO[
    AggregateViewStore[View, Query, Aggregates],
    Throwable,
    Option[View]
  ] = for
    res <- ZIO
      .environmentWithZIO[AggregateViewStore[View, Query, Aggregates]](
        _.get
          .readAggregateViewAndStatus(query)
      )
    (currView, status) = res
    viewRes <- ZIO
      .environmentWithZIO[
        AggregateViewStore[View, Query, Aggregates]
      ](
        _.get
          .streamEventsFrom(
            query,
            status.map(_.next)
          )
          .unchunks
          .fold[Either[
            AggregateViewError,
            Option[
              View
            ]
          ]](Right(currView))((prev, ev) =>
            for
              prev_ <- prev
              res <- view.aggregate(prev_, ev).map(Some(_))
            yield res
          )
          .compile
          .toList
          .flatMap(
            _.headOption
              .fold(ZIO.none)(ZIO.fromEither)
          )
      )
  yield viewRes
