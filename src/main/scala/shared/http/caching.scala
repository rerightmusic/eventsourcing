package shared.http

import cats.effect.*
import cats.implicits.*
import org.http4s.*
import zio.*
import zio.interop.catz.*
import org.typelevel.ci.*

object caching:
  def noCaching[R](route: HttpRoutes[RIO[R, *]]): HttpRoutes[RIO[R, *]] =
    cats.data.Kleisli { req =>
      route(req).map(resp =>
        resp.putHeaders(Header.Raw(ci"Cache-control", "no-store"))
      )
    }
