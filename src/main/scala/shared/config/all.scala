package shared.config

import cats.implicits.*
import zio.blocking.{Blocking, effectBlocking}
import zio.interop.catz.*
import zio.{RIO, ZIO, blocking}
import java.io.{File, FileInputStream}
import java.util.Properties
import scala.collection.JavaConverters.*

object all:
  def getEnvVars(envFiles: List[File]): RIO[Blocking, Map[String, String]] =
    for
      res <- envFiles
        .traverse(f =>
          val fis = new FileInputStream(f)
          val prop_ = new Properties()
          for _ <- effectBlocking(prop_.load(fis))
          yield prop_.asScala.toMap
        )
        .map(_.flatten.toMap)
      env <- blocking.effectBlocking(System.getenv.asScala)
    yield res.map((k, v) => (k, env.getOrElse(k, v).replaceAll("^\"|\"$", "")))
