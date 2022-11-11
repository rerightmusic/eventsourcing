package shared.config

import zio.ZIO
import shared.data.all.*
import scala.util.Properties

enum Env:
  case test
  case local
  case dev
  case prod

object Env:
  def getEnv =
    Properties.envOrNone("ENV") match
      case None          => Env.local
      case Some("test")  => Env.test
      case Some("local") => Env.local
      case Some("dev")   => Env.dev
      case Some("prod")  => Env.prod
      case Some(_)       => Env.local
