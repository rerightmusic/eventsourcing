lazy val projectName = "eventsourcing"
lazy val root = project
  .in(file("."))
  .settings(
    name := projectName,
    developers := List(
      Developer(
        "pavlosgi",
        "Pavlos Georgiou",
        "",
        url("http://")
      )
    ),
    licenses := List(
      "MIT" -> url("https://mit-license.org")
    ),
    publish / skip := true,
    Global / onChangedBuildSource := ReloadOnSourceChanges,
    scalaVersion := "3.2.0",
    scalacOptions ++= Seq(
      "-Ykind-projector:underscores",
      "-Xfatal-warnings",
      "-Ycheck-all-patmat",
      "-Xmax-inlines",
      "100",
      "-language:postfixOps",
      "-language:implicitConversions"
    ),
    testFrameworks := Seq(TestFrameworks.ScalaTest),
    Test / parallelExecution := false,
    libraryDependencies ++= Seq(
      "dev.zio" %% "izumi-reflect" % "2.1.0-M1",
      "dev.zio" %% "zio" % "1.0.13",
      "dev.zio" %% "zio-prelude" % "1.0.0-RC8",
      "dev.zio" %% "zio-logging" % "0.5.14",
      "dev.zio" %% "zio-json" % "0.2.0-M4",
      "ch.qos.logback" % "logback-classic" % "1.2.11",
      "dev.zio" %% "zio-interop-cats" % "3.2.9.1",
      "org.http4s" %% "http4s-blaze-server" % "0.23.6",
      "org.http4s" %% "http4s-dsl" % "0.23.6",
      "org.scalatest" %% "scalatest" % "3.2.10" % Test,
      "co.fs2" %% "fs2-core" % "3.2.5"
    ) ++ doobie
  )

lazy val doobie = Seq(
  "org.tpolecat" %% "doobie-core" % "1.0.0-RC2",
  "org.tpolecat" %% "doobie-h2" % "1.0.0-RC2",
  "org.tpolecat" %% "doobie-hikari" % "1.0.0-RC2",
  "org.tpolecat" %% "doobie-postgres" % "1.0.0-RC2",
  "org.tpolecat" %% "doobie-scalatest" % "1.0.0-RC2" % "test"
)
