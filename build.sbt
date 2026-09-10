ThisBuild / scalaVersion := "3.9.0"
ThisBuild / organization := "net.ghoula"
ThisBuild / organizationName := "Hakim Jonas Ghoula"
ThisBuild / versionScheme := Some("early-semver")
ThisBuild / semanticdbEnabled := true
ThisBuild / semanticdbVersion := scalafixSemanticdb.revision

ThisBuild / licenses := Seq("LGPL-3.0-or-later" -> url("https://www.gnu.org/licenses/lgpl-3.0.txt"))
ThisBuild / homepage := Some(url("https://github.com/hakimjonas/melian"))
ThisBuild / description := "A zero-reflection, compile-time web framework for Scala 3 built on the Arda ecosystem"
// Validate quotes/splices at expansion time: the test suites exercise every RouteMacros
// expansion, so ill-typed trees surface in CI, not at user sites.
ThisBuild / Test / scalacOptions += "-Xcheck-macros"
ThisBuild / developers := List(
  Developer(
    id = "hakimjonas",
    name = "Hakim Jonas Ghoula",
    email = "hakim@ghoula.net",
    url = url("https://github.com/hakimjonas")
  )
)
ThisBuild / scmInfo := Some(
  ScmInfo(
    url("https://github.com/hakimjonas/melian"),
    "scm:git@github.com:hakimjonas/melian.git"
  )
)

// ===== Publishing Settings =====
//
// Maven Central (Central Portal) is the single publication target. Releases are staged locally
// and uploaded with `sonaRelease` (sbt 2.x built-in Central Portal support); artifacts are signed
// by sbt-pgp (`publishSigned`). Credentials are read automatically from SONATYPE_USERNAME /
// SONATYPE_PASSWORD.
ThisBuild / publishTo := {
  val centralSnapshots = "https://central.sonatype.com/repository/maven-snapshots/"
  if (isSnapshot.value) Some("central-snapshots" at centralSnapshots)
  else localStaging.value
}
ThisBuild / publishMavenStyle := true
ThisBuild / pomIncludeRepository := { _ => false }
ThisBuild / Test / publishArtifact := false

// ===== Compiler Settings =====
javacOptions ++= Seq("--release", "25")

val sharedScalacOptions: Seq[String] = Seq(
  "-deprecation",
  "-feature",
  "-unchecked",
  "-Werror",
  "-Yexplicit-nulls",
  "-language:strictEquality",
  "-Wsafe-init",
  "-Wunused:all",
  "-Wvalue-discard",
  "-Wrecurse-with-default",
  "-no-indent"
)

// ===== Dependency Versions =====
val eruHttpVersion: String = "1.0.0-alpha.6"
val saratiVersion: String = "1.0.0-alpha.5"
val rumilVersion: String = "1.0.0-alpha.13"
val valarVersion: String = "0.6.0"
val munitVersion: String = "1.3.6"

// ===== Modules =====

lazy val core = (project in file("melian-core"))
  .settings(
    name := "melian-core",
    scalacOptions ++= sharedScalacOptions,
    libraryDependencies ++= Seq(
      "net.ghoula" %% "eru-http-core" % eruHttpVersion,
      "org.scalameta" %% "munit" % munitVersion % Test
    ),
    javaOptions ++= Seq("-XX:+UseZGC"),
    Test / fork := true
  )

lazy val router = (project in file("melian-router"))
  .settings(
    name := "melian-router",
    scalacOptions ++= sharedScalacOptions,
    libraryDependencies ++= Seq(
      "net.ghoula" %% "sarati" % saratiVersion,
      "net.ghoula" %% "rumil-parsers" % rumilVersion,
      "net.ghoula" %% "valar-core" % valarVersion,
      // WebSocket routes perform the RFC 6455 upgrade through eru-http's server runtime.
      "net.ghoula" %% "eru-http-server" % eruHttpVersion,
      // End-to-end WebSocket tests drive a live server with eru-http's own client.
      "net.ghoula" %% "eru-http-client" % eruHttpVersion % Test,
      "org.scalameta" %% "munit" % munitVersion % Test
    ),
    javaOptions ++= Seq("-XX:+UseZGC"),
    Test / fork := true
  )
  .dependsOn(core)

lazy val openapi = (project in file("melian-openapi"))
  .settings(
    name := "melian-openapi",
    scalacOptions ++= sharedScalacOptions,
    libraryDependencies ++= Seq(
      "net.ghoula" %% "sarati" % saratiVersion,
      "org.scalameta" %% "munit" % munitVersion % Test
    ),
    javaOptions ++= Seq("-XX:+UseZGC"),
    Test / fork := true
  )
  .dependsOn(core)

lazy val server = (project in file("melian-server"))
  .settings(
    name := "melian-server",
    scalacOptions ++= sharedScalacOptions,
    libraryDependencies ++= Seq(
      "net.ghoula" %% "eru-http-server" % eruHttpVersion,
      // ACME provisioning (DESIGN.md Section 14.1): TLS is eru-http's domain; Melian wires the
      // result into HttpServerConfig.withTls.
      "net.ghoula" %% "eru-http-acme" % eruHttpVersion,
      "org.scalameta" %% "munit" % munitVersion % Test
    ),
    javaOptions ++= Seq("-XX:+UseZGC"),
    Test / fork := true
  )
  .dependsOn(core, router)

lazy val testKit = (project in file("melian-test"))
  .settings(
    name := "melian-test",
    scalacOptions ++= sharedScalacOptions,
    libraryDependencies ++= Seq(
      // The websocket helper runs a live server and drives it with eru-http's own client.
      "net.ghoula" %% "eru-http-server" % eruHttpVersion,
      "net.ghoula" %% "eru-http-client" % eruHttpVersion,
      "org.scalameta" %% "munit" % munitVersion % Test
    ),
    javaOptions ++= Seq("-XX:+UseZGC"),
    Test / fork := true
  )
  .dependsOn(core, router)

lazy val docTool = project
  .in(file("docTool"))
  .settings(
    name := "melian-doc-tool",
    scalacOptions ++= sharedScalacOptions,
    publish / skip := true,
    libraryDependencies ++= Seq(
      "org.scalameta" %% "scalameta" % "4.17.3",
      "org.scalatest" %% "scalatest" % "3.2.20" % Test
    ),
    Compile / run / mainClass := Some("net.ghoula.melian.doctool.DocCoverageMain")
  )

lazy val root = (project in file("."))
  .settings(
    name := "melian",
    publish / skip := true
  )
  .aggregate(core, router, openapi, server, testKit, docTool)

// ===== Command Aliases =====
addCommandAlias(
  "testAll",
  "core/Test/testFull; router/Test/testFull; openapi/Test/testFull; server/Test/testFull; testKit/Test/testFull; docTool/Test/testFull"
)
addCommandAlias("prepare", "scalafmtAll; scalafmtSbt; scalafixAll")
addCommandAlias("check", "docCoverage; scalafixAll --check; scalafmtCheckAll; scalafmtSbtCheck")
addCommandAlias(
  "docCoverage",
  "docTool/run check doc-coverage.json melian-core/src/main/scala melian-router/src/main/scala melian-openapi/src/main/scala melian-server/src/main/scala melian-test/src/main/scala"
)
addCommandAlias(
  "docCoverageSnapshot",
  "docTool/run snapshot doc-coverage.json melian-core/src/main/scala melian-router/src/main/scala melian-openapi/src/main/scala melian-server/src/main/scala melian-test/src/main/scala"
)
