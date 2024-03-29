package eventsourcing.infra

import org.http4s.HttpRoutes
import zio.RIO
import eventsourcing.domain.AggregateViewClass
import eventsourcing.domain.AggregateViewService
import eventsourcing.domain.AggregateViewStore
import eventsourcing.domain.types.*
import org.http4s.server.middleware.CORS
import org.http4s.dsl.Http4sDsl
import zio.interop.catz.*
import zio.interop.catz.implicits.*
import shared.health.HealthService
import zio.{ZIO, Ref}
import zio.Fiber
import shared.health.HealthCheck
import org.http4s.blaze.server.BlazeServerBuilder
import scala.concurrent.duration.*
import org.http4s.server.Router
import shared.json.all.{*, given}
import shared.http.all.{*, given}
import cats.syntax.all.*
import org.http4s.headers.*

abstract class ViewOps[R]:
  def name: String
  def reset: RIO[R, Unit]
  def health: RIO[R, HealthCheck]
  def isCaughtUp: RIO[R, Boolean]
  def run: RIO[R, Unit]

trait HttpAggregateViews[Aggs <: Tuple, R] extends Http4sDsl[RIO[R, *]]:
  val cors = CORS.policy.withAllowOriginAll.withAllowCredentials(false)

  // Change to map
  def viewOps: List[ViewOps[R]]

  def run(port: Int, host: String) =
    for
      ref <- Ref.make[Map[String, Fiber.Runtime[Throwable, Unit]]](Map())
      fiber <- BlazeServerBuilder[RIO[R, *]]
        .withIdleTimeout(25.minutes)
        .withResponseHeaderTimeout(20.minutes)
        .bindHttp(port, host)
        .withHttpWebSocketApp(builder =>
          noCaching(
            Router[RIO[R, *]](
              (viewOps
                .map(v =>
                  v.name -> (HttpRoutes.of[RIO[R, *]] {
                    case GET -> Root =>
                      for
                        ref_ <- ref.get
                        isRunning <- ref_
                          .get(v.name)
                          .traverse(_.status.map(!_.isDone))
                        res <- HealthService.apply_(v.health.map(List(_)))
                        res_ <- res match
                          case Left(h) =>
                            InternalServerError(
                              Map(
                                "health" -> h.toJsonASTOrFail,
                                "fiber_running" -> isRunning.toJsonASTOrFail
                              ).toJsonASTOrFail
                            )
                          case Right(h) =>
                            Ok(
                              Map(
                                "health" -> h.toJsonASTOrFail,
                                "fiber_running" -> isRunning.toJsonASTOrFail
                              ).toJsonASTOrFail
                            )
                      yield res_

                    case GET -> Root / "reset" =>
                      for
                        _ <- v.reset
                        _ <- ZIO.logInfo(s"${v.name} was reset")
                        res <- Ok(
                          Map("message" -> s"Reset ${v.name}").toJsonASTOrFail
                        )
                      yield res
                  })
                )) ++ List("/" -> (HttpRoutes.of[RIO[R, *]] {
                case req @ GET -> Root / "health" =>
                  for
                    ref_ <- ref.get
                    res <- Ok(
                      viewOps
                        .traverse(x =>
                          for
                            res <- HealthService.apply_(x.health.map(List(_)))
                            notRunning <- ref_
                              .get(x.name)
                              .traverse(_.status.map(_.isDone))
                              .map(_.getOrElse(true))
                          yield x.name -> Map(
                            "healthy" -> (if res.isLeft || notRunning then
                                            "Unhealthy"
                                          else "Healthy").toJsonASTOrFail,
                            "url" -> s"${req.headers
                              .get[`X-Forwarded-Proto`]
                              .fold("http")(_.scheme.value)}://${req.headers
                              .get[Host]
                              .map(h => s"${h.host}${h.port.fold("")(p => s":$p")}")
                              .getOrElse("")}/${x.name}".toJsonASTOrFail
                          )
                        )
                        .map(_.toMap.toJsonASTOrFail)
                    )
                  yield res
                case req @ GET -> Root / "status" =>
                  for
                    caughtUps <- viewOps.traverse(x => x.isCaughtUp)
                    res <-
                      if caughtUps.forall(x => x) then
                        Ok(Map("Result" -> "Ready").toJsonASTOrFail)
                      else
                        ServiceUnavailable(
                          Map("Result" -> "Catching Up").toJsonASTOrFail
                        )
                  yield res
                case req @ GET -> Root / "reset" =>
                  for
                    _ <- viewOps.traverse(x => x.reset)
                    res <- Ok(
                      Map(
                        "message" -> viewOps.map(x => s"Reset ${x.name}")
                      ).toJsonASTOrFail
                    )
                  yield res
              })): _*
            )
          ).orNotFound
        )
        .resource
        .toManagedZIO
        .useForever
        .fork
      fibers <- viewOps
        .traverse(v => v.run.fork.map(v.name -> _))
        .map(_.toMap)
      _ <- ref.set(fibers)
      _ <- (List(fiber) ++ fibers.values.toList).fold(Fiber.unit)(_ <> _).join
    yield ()

object HttpAggregateViews:
  def apply[Aggs <: NonEmptyTuple] = new HttpAggregateViewsDsl[Aggs]
  class HttpAggregateViewsDsl[Aggs <: NonEmptyTuple]:
    def run[R](using
      r: HttpAggregateViews[Aggs, R]
    ) =
      r.run

  given empty[R]: HttpAggregateViews[EmptyTuple, R] =
    new HttpAggregateViews[EmptyTuple, R]:
      def viewOps = List()

  given views[
    View,
    Rest <: Tuple,
    R <: AggregateViewService[View]
  ](using
    view: AggregateViewClass[View],
    rest: HttpAggregateViews[Rest, R],
    tSvc: zio.Tag[
      AggregateViewService.Aux[
        View,
        view.ActualView,
        view.Query,
        view.Aggregates
      ]
    ],
    tStore: zio.Tag[
      AggregateViewStore[view.ActualView, view.Query, view.Aggregates]
    ]
  ): HttpAggregateViews[View *: Rest, R] =
    new HttpAggregateViews[View *: Rest, R]:
      def viewOps: List[ViewOps[R]] = new ViewOps {
        def name: String = view.instance.versionedStoreName
        def reset: RIO[R, Unit] =
          AggregateViewService[View].store(_.resetAggregateView)
        def health: RIO[R, HealthCheck] =
          AggregateViewService[View].store(x => ZIO.succeed(x.health))
        def isCaughtUp: RIO[R, Boolean] = AggregateViewService[View].isCaughtUp
        def run: RIO[R, Unit] =
          AggregateViewService[View].run(AggregateViewMode.Continue, true)
      } :: rest.viewOps
