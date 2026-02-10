
resolvers += "Typesafe Releases" at "https://repo.typesafe.com/typesafe/releases/"

val skuberVersion = "3.1.0"

// Core dependencies
val scalaTest = "org.scalatest" %% "scalatest" % "3.2.19"
val mockito = "org.mockito" % "mockito-core" % "5.21.0"
val scalaTestMockito = "org.scalatestplus" %% "mockito-5-18" % "3.2.19.0"
val typesafeConfig = "com.typesafe" % "config" % "1.4.5"
val logback = "ch.qos.logback" % "logback-classic" % "1.5.28" % Runtime
val playJson = "org.playframework" %% "play-json" % "3.0.6"

// Pekko dependencies
val pekkoGroup = "org.apache.pekko"
val pekkoVersion = "1.3.0"

val pekkoSlf4j = pekkoGroup %% "pekko-slf4j" % pekkoVersion
val pekkoStream = pekkoGroup %% "pekko-stream" % pekkoVersion
val pekkoStreamTestkit = pekkoGroup %% "pekko-stream-testkit" % pekkoVersion
val pekkoActors = pekkoGroup %% "pekko-actor" % pekkoVersion

// Skuber dependencies - these are external library dependencies
val skuberCore = "io.skuber" %% "skuber-core" % skuberVersion
val skuberPekko = "io.skuber" %% "skuber-pekko" % skuberVersion

ThisBuild / version := "0.1.0-SNAPSHOT"

ThisBuild / organization := "io.skuber"

sonatypeProfileName := "io.skuber"

ThisBuild / publishMavenStyle := true

ThisBuild / licenses := Seq("APL2" -> url("http://www.apache.org/licenses/LICENSE-2.0.txt"))

ThisBuild / homepage := Some(url("https://github.com/skuber-io/skuber-operator"))

ThisBuild / scmInfo := Some(
  ScmInfo(
    url("https://github.com/skuber-io/skuber-operator"),
    "scm:git@github.com:skuber-io/skuber-operator.git"
  )
)

ThisBuild / developers := List(
  Developer(id = "doriordan", name = "David ORiordan", email = "doriordan@gmail.com", url = url("https://github.com/doriordan"))
)

lazy val commonSettings = Seq(
  // Scala 3.8+ required for macro annotations
  scalaVersion := "3.8.1",
  crossScalaVersions := Seq("3.8.1"),
  // Enable experimental for MacroAnnotation
  scalacOptions ++= Seq("-Xcheck-macros", "-experimental"),
  publishTo := {
    val centralSnapshots = "https://central.sonatype.com/repository/maven-snapshots/"
    if (isSnapshot.value) Some("central-snapshots" at centralSnapshots)
    else localStaging.value
  },
  pomIncludeRepository := { _ => false },
  Test / classLoaderLayeringStrategy := ClassLoaderLayeringStrategy.Flat
)

// Operator core module - support for building Kubernetes operators with custom resources
lazy val operator = (project in file("operator"))
  .settings(
    name := "skuber-operator",
    commonSettings,
    libraryDependencies ++= Seq(
      skuberCore,
      skuberPekko,
      playJson,
      pekkoActors,
      pekkoStream,
      pekkoSlf4j,
      scalaTest % Test,
      pekkoStreamTestkit % Test
    )
  )

// Integration tests - tests the operator module functionality against a real Kubernetes cluster
lazy val integration = (project in file("integration"))
  .settings(
    name := "skuber-operator-integration",
    publish / skip := true,
    commonSettings,
    libraryDependencies ++= Seq(
      scalaTest % Test,
      scalaTestMockito % Test,
      typesafeConfig % Test,
      "ch.qos.logback" % "logback-classic" % "1.5.28" % Test
    ),
    Test / fork := false,
    Test / parallelExecution := false
  )
  .dependsOn(operator)

// Examples - demonstrates building Kubernetes operators with Skuber
lazy val examples = (project in file("examples"))
  .settings(
    name := "skuber-operator-examples",
    publish / skip := true,
    commonSettings,
    libraryDependencies ++= Seq(
      pekkoActors,
      pekkoStream,
      pekkoSlf4j,
      logback,
      typesafeConfig,
      scalaTest % Test
    ),
    // Allow running the main class
    Compile / run / fork := true
  )
  .dependsOn(operator)

lazy val root = (project in file("."))
  .settings(
    name := "skuber-operator-root",
    publish / skip := true,
    commonSettings
  )
  .aggregate(operator, integration, examples)

root / publishArtifact := false
