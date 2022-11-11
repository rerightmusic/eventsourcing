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
    resolvers ++= Seq(
      "Sonatype Snapshots" at "https://oss.sonatype.org/content/repositories/snapshots"
    ),
    testFrameworks := Seq(TestFrameworks.ScalaTest),
    Test / parallelExecution := false,
    libraryDependencies ++= Seq(
      "com.softwaremill.sttp.client3" %% "core" % "3.3.16",
      "com.softwaremill.sttp.client3" %% "zio" % "3.8.3",
      "com.softwaremill.sttp.client3" %% "zio-json" % "3.8.3",
      "net.logstash.logback" % "logstash-logback-encoder" % "7.2",
      "dev.zio" %% "zio" % "2.0.2",
      "dev.zio" %% "zio-prelude" % "1.0.0-RC16",
      "dev.zio" %% "zio-logging" % "2.1.2",
      "dev.zio" %% "zio-logging-slf4j" % "2.1.2",
      "dev.zio" %% "zio-json" % "0.3.0+24-57885a7c-SNAPSHOT",
      "ch.qos.logback" % "logback-classic" % "1.2.11",
      "dev.zio" %% "zio-interop-cats" % "3.3.0",
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
