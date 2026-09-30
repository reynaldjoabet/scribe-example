ThisBuild / scalaVersion := "3.9.0"
ThisBuild / version      := "0.1.0-SNAPSHOT"

ThisBuild / crossScalaVersions := Seq("3.3.8","3.9.0")

ThisBuild / scalacOptions := Seq(
  "-encoding",
  "UTF-8",
  "-no-indent",
  "-deprecation",
  "-feature",
  "-unchecked",
  // "-Werror",
  // "-Wunused:all",
  "-Wvalue-discard",
  "-Wnonunit-statement",
  "-language:strictEquality",
  "-Xcheck-macros",
  "-Xmax-inlines:64"
)
// scribe-slf4j2 is an SLF4J 2 provider; pin the API so no dependency can drag it back to 1.7.x
ThisBuild / dependencyOverrides += "org.slf4j" % "slf4j-api" % "2.0.17"

val scribe = "3.19.0"
val http4s = "0.23.30"
val zio = "2.1.21"
val zioHttp = "3.5.1"
val scalaTest = "3.2.19"

lazy val root = project
  .in(file("."))
  .aggregate(common, catsApp, zioApp)
  .settings(name := "scribe-example", publish / skip := true)

// Domain models and effect-agnostic logging: JSON format, async stdout writer, Scribe setup
lazy val common = project.settings(
  libraryDependencies ++= Seq(
    "com.outr" %% "scribe"            % scribe,
    "com.outr" %% "scribe-json-circe" % scribe,
    "com.outr" %% "scribe-slf4j2"     % scribe
  )
)

// cats-effect + http4s app: Log[F] / LogContext (IOLocal) on top of Scribe
lazy val catsApp = project
  .in(file("cats"))
  .dependsOn(common)
  .settings(
    libraryDependencies ++= Seq(
      "com.outr"      %% "scribe-cats"         % scribe,
      "org.http4s"    %% "http4s-ember-server" % http4s,
      "org.http4s"    %% "http4s-dsl"          % http4s,
      "org.http4s"    %% "http4s-circe"        % http4s,
      "org.typelevel" %% "log4cats-slf4j"      % "2.7.0",
      "org.scalatest" %% "scalatest"           % scalaTest % Test
    ),
    // specs assert on log data typed Map[String, Any], which strict equality can't compare
    Test / scalacOptions -= "-language:strictEquality",
    run / fork := true,
    Test / fork := true
  )

// ZIO + zio-http app: ZIO's built-in logging (ZIO.logInfo, logAnnotate) with Scribe as the backend
lazy val zioApp = project
  .in(file("zio"))
  .dependsOn(common)
  .settings(
    libraryDependencies ++= Seq(
      "dev.zio"       %% "zio"       % zio,
      "dev.zio"       %% "zio-http"  % zioHttp,
      "org.scalatest" %% "scalatest" % scalaTest % Test
    ),
    // ZIO's provide/layer macros emit trees that -Xcheck-macros rejects
    scalacOptions -= "-Xcheck-macros",
    Test / scalacOptions -= "-language:strictEquality",
    run / fork := true,
    Test / fork := true
  )
