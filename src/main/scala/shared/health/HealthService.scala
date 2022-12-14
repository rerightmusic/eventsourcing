package shared.health

import shared.json.all.*
import org.http4s.HttpRoutes
import org.http4s.dsl.Http4sDsl
import org.http4s.server.middleware.CORS
import zio.interop.catz.*
import zio.{RIO, Task, ZIO}
import org.http4s.server.middleware.CORSConfig
import org.http4s.Method
import scala.concurrent.duration.*
import language.unsafeNulls
import cats.syntax.all.*
import shared.http.all.given

sealed trait Health derives JsonCodec
case class Healthy(details: Option[Json] = None) extends Health
case class Unhealthy(details: Option[Json] = None) extends Health

sealed trait HealthCheck
case class LeafHealthCheck(name: String, check: () => Task[Health])
    extends HealthCheck
case class NodeHealthCheck(name: String, checks: List[HealthCheck])
    extends HealthCheck

sealed trait EvalHealthCheck derives JsonCodec
case class EvalLeafHealthCheck(name: String, check: Health)
    extends EvalHealthCheck
case class EvalNodeHealthCheck(name: String, checks: List[EvalHealthCheck])
    extends EvalHealthCheck

object HealthService:
  private def allHealthy(checks: List[EvalHealthCheck]): Boolean =
    checks.forall {
      case EvalLeafHealthCheck(_, Healthy(_))   => true
      case EvalLeafHealthCheck(_, Unhealthy(_)) => false
      case EvalNodeHealthCheck(_, h)            => allHealthy(h)
    }

  val cors = CORS.policy.withAllowOriginAll
    .withAllowCredentials(false)

  def apply[R](
    checks: RIO[R, List[HealthCheck]]
  ): (String, HttpRoutes[RIO[R, *]]) =
    object dsl extends Http4sDsl[RIO[R, *]]
    import dsl.*

    def evalHealthCheck(
      checks: HealthCheck
    ): Task[EvalHealthCheck] =
      checks match
        case LeafHealthCheck(name, check) =>
          check().map(EvalLeafHealthCheck(name, _))
        case NodeHealthCheck(name, checks) =>
          for
            res <- ZIO.foreach(checks)(
              evalHealthCheck
            )
          yield EvalNodeHealthCheck(name, res)

    "/health" -> cors(HttpRoutes.of[RIO[R, *]] { case GET -> Root =>
      for
        res <- apply_(checks)
        res_ <- res match
          case Left(h)  => InternalServerError(h.toJsonASTOrFail)
          case Right(h) => Ok(h.toJsonASTOrFail)
      yield res_
    })

  def apply_[R](
    checks: RIO[R, List[HealthCheck]]
  ) =
    object dsl extends Http4sDsl[RIO[R, *]]
    import dsl.*

    def evalHealthCheck(
      checks: HealthCheck
    ): Task[EvalHealthCheck] =
      checks match
        case LeafHealthCheck(name, check) =>
          check().map(EvalLeafHealthCheck(name, _))
        case NodeHealthCheck(name, checks) =>
          for
            res <- ZIO.foreach(checks)(
              evalHealthCheck
            )
          yield EvalNodeHealthCheck(name, res)

    for
      c <- checks
      healthChecks <- ZIO.foreach(c)(evalHealthCheck)
      res <-
        if (allHealthy(healthChecks))
          ZIO.right(healthChecks)
        else ZIO.left(healthChecks)
    yield res
