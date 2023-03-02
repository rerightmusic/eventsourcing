package shared.error

import zio.Cause
import zio.ZIO

object all:
  def logErrorCauseSquashed(err: String, cause: Cause[Any]) =
    val pattern = "\"zio-fiber-([0-9]*)\"".r
    val linesMap = cause.prettyPrint
      .split("\n")
      .zipWithIndex
      .foldLeft(Map.empty[String, (Int, Set[String])])((p, n) => {
        val fiberId =
          pattern.findAllMatchIn(n._1).toList.flatMap(_.subgroups).toSet
        val excludeFiberId = n._1.replaceAll("\"zio-fiber-[0-9]*\"", "").trim
        p.updatedWith(excludeFiberId)(x =>
          x.fold(Some(n._2 -> fiberId))(x_ => Some(x_._1 -> (x_._2 ++ fiberId)))
        )
      })

    val causeMsg = linesMap.toList
      .sortBy(x => x._2._1)
      .map(l =>
        if l._2._2.isEmpty then l._1
        else s"""${l._1} "zio-fiber-${l._2._2.mkString(", ")}"""
      )
      .mkString("\n")
    ZIO.logError(s"$err\n$causeMsg")
