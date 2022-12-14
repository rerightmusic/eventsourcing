package shared.config

import cats.implicits.*
import cats.syntax.*
import zio.interop.catz.*
import zio.{RIO, ZIO}
import shared.logging.all.*
import shared.data.all.*
import java.io.{File, FileInputStream}
import java.util.Properties
import scala.collection.JavaConverters.*
import scala.util.{Properties as SProperties}
import zio.ZIO
import zio.ZIO.attemptBlocking

object all:
  def getEnvVars(
    envFileEnvName: String,
    ifMissing: Option[String] = None
  ): RIO[Any, Map[String, String]] =
    for
      envFile <- SProperties
        .envOrNone(envFileEnvName)
        .orElse(ifMissing)
        .getOrFail(
          s"${envFileEnvName} is missing"
        )
      res <- getEnvVars(List(new File(envFile)))
    yield res

  def getEnvVars(
    envFiles: List[File]
  ): RIO[Any, Map[String, String]] =
    for
      _ <- ZIO.logInfo(s"Loading env vars from ${envFiles.mkString(", ")}")
      res <- envFiles
        .traverse(f =>
          val fis = new FileInputStream(f)
          val prop_ = new Properties()
          for _ <- attemptBlocking(prop_.load(fis))
          yield prop_.asScala.toMap
        )
        .map(_.flatten.toMap)
      env <- ZIO.attemptBlocking(System.getenv.asScala)
    yield res.map((k, v) => (k, env.getOrElse(k, v).replaceAll("^\"|\"$", "")))

  export Env.*
