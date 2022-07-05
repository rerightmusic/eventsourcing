package eventsourcing.infra

import org.http4s.HttpRoutes
import zio.RIO
import org.http4s.server.middleware.CORSPolicy
import eventsourcing.domain.AggregateViewClass
import eventsourcing.domain.AggregateViewService
import eventsourcing.domain.AggregateViewStore
import eventsourcing.domain.types.*
import org.http4s.server.middleware.CORS
import org.http4s.dsl.Http4sDsl
import zio.interop.catz.*
import zio.interop.catz.implicits.*
import cats.arrow.FunctionK
import cats.data.OptionT
import shared.health.HealthService
import zio.ZIO
import zio.Fiber
import shared.health.HealthCheck
import izumi.reflect.Tag
import org.http4s.blaze.server.BlazeServerBuilder
import scala.concurrent.duration.*
import org.http4s.server.Router
import shared.logging.all.Logging
import shared.json.all.*
import shared.http.json.given

trait HttpAggregateViews[Aggs <: Tuple, R] extends Http4sDsl[RIO[R, *]]:
  val cors = CORS.policy.withAllowOriginAll.withAllowCredentials(false)

  def opsRoutes: List[(String, HttpRoutes[RIO[R, *]])]
  def routes = opsRoutes ++ List(
    HealthService[R](
      for {
        c <- checks
      } yield c
    )
  )

  def isCaughtUp: RIO[R, Boolean]

  def runViews: RIO[R & Logging, List[Fiber.Runtime[Throwable, Unit]]]

  def run(port: Int, host: String) =
    for
      fiber <- BlazeServerBuilder[RIO[R, *]]
        .withIdleTimeout(25.minutes)
        .withResponseHeaderTimeout(20.minutes)
        .bindHttp(port, host)
        .withHttpWebSocketApp(builder =>
          Router[RIO[R, *]](
            (List("status" -> HttpRoutes.of[RIO[R, *]] { case GET -> Root =>
              for
                c <- isCaughtUp
                res <-
                  if c then
                    Ok(
                      Map[String, String]("status" -> s"Ready").toJsonASTOrFail
                    )
                  else
                    ServiceUnavailable(
                      Map[String, String](
                        "status" -> s"Catching up"
                      ).toJsonASTOrFail
                    )
              yield res
            }) ++ routes): _*
          ).orNotFound
        )
        .serve
        .compile
        .drain
        .fork
      fibers <- runViews
      _ <- (List(fiber) ++ fibers).fold(Fiber.unit)(_ <> _).join
    yield ()

  def checks: RIO[R, List[HealthCheck]]

object HttpAggregateViews:
  def apply[Aggs <: NonEmptyTuple] = new HttpAggregateViewsDsl[Aggs]
  class HttpAggregateViewsDsl[Aggs <: NonEmptyTuple]:
    def run[R](using
      r: HttpAggregateViews[Aggs, R]
    ) =
      r.run

  given empty[R]: HttpAggregateViews[EmptyTuple, R] =
    new HttpAggregateViews[EmptyTuple, R]:
      def opsRoutes = List()
      def checks = ZIO.succeed(List())
      def runViews = ZIO.succeed(List())

      def isCaughtUp = ZIO.succeed(true)

  given views[
    View,
    Rest <: Tuple,
    R <: AggregateViewService[View]
  ](using
    view: AggregateViewClass[View],
    rest: HttpAggregateViews[Rest, R],
    t: Tag[
      AggregateViewService.Service[View] {
        type ActualView = view.ActualView
        type Query = view.Query
        type Aggregates = view.Aggregates
      }
    ]
  ): HttpAggregateViews[View *: Rest, R] =
    new HttpAggregateViews[View *: Rest, R]:
      def checks =
        for {
          h1 <- AggregateViewService[View]
            .store(x => ZIO.succeed(x.health))
          h2 <- rest.checks
        } yield h1 :: h2

      def opsRoutes =
        s"/reset/${view.instance.storeName}" -> cors(
          HttpRoutes.of[RIO[R, *]] { case GET -> Root =>
            for
              _ <- AggregateViewService[View]
                .store(
                  _.resetAggregateView
                )
              res <- Ok.apply(
                Map[String, String](
                  "result" -> s"${view.instance.storeName} was reset"
                ).toJsonASTOrFail
              )
            yield res
          }
        ) :: rest.opsRoutes

      def isCaughtUp = for
        r <- AggregateViewService[View].isCaughtUp
        r2 <- rest.isCaughtUp
      yield r && r2

      def runViews = for
        fiber <- AggregateViewService[View]
          .run(AggregateViewMode.Continue, true)
          .tapError(e => Logging.error(e.toString))
          .fork

        fibers <- rest.runViews
      yield fiber :: fibers
