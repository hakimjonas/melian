ThisBuild / scalaVersion := "3.8.3"
ThisBuild / organization := "net.ghoula"
ThisBuild / organizationName := "Hakim Jonas Ghoula"
ThisBuild / versionScheme := Some("early-semver")
ThisBuild / semanticdbEnabled := true
ThisBuild / semanticdbVersion := scalafixSemanticdb.revision

ThisBuild / licenses := List("MIT" -> url("https://opensource.org/licenses/MIT"))
ThisBuild / homepage := Some(url("https://codeberg.org/hakim/melian"))
ThisBuild / description := "A zero-reflection, compile-time web framework for Scala 3 built on the Arda ecosystem"
ThisBuild / developers := List(
  Developer(
    id = "hakimjonas",
    name = "Hakim Jonas Ghoula",
    email = "hakim@ghoula.net",
    url = url("https://codeberg.org/hakim")
  )
)
ThisBuild / scmInfo := Some(
  ScmInfo(
    url("https://codeberg.org/hakim/melian"),
    "scm:git@codeberg.org:hakim/melian.git"
  )
)

// ===== Publishing Settings =====
val forgejoHost: String = sys.env.getOrElse("FORGEJO_HOST", "localhost")
val forgejoUrl: String = s"http://$forgejoHost:3000"

ThisBuild / publishTo := {
  if (sys.env.contains("CODEBERG_TOKEN"))
    Some("codeberg" at "https://codeberg.org/api/packages/hakim/maven")
  else
    Some(("local-forgejo" at s"$forgejoUrl/api/packages/hakim/maven").withAllowInsecureProtocol(true))
}
ThisBuild / publishMavenStyle := true
ThisBuild / Test / publishArtifact := false

ThisBuild / resolvers ++= Seq(
  "codeberg" at "https://codeberg.org/api/packages/hakim/maven",
  ("local-forgejo" at s"$forgejoUrl/api/packages/hakim/maven").withAllowInsecureProtocol(true)
)

ThisBuild / credentials ++= sys.env
  .get("CODEBERG_TOKEN")
  .map { token =>
    Credentials("Gitea Package API", "codeberg.org", "hakim", token)
  }
  .toSeq

ThisBuild / credentials ++= sys.env
  .get("FORGEJO_TOKEN")
  .map { token =>
    Credentials("Gitea Package API", forgejoHost, "hakim", token)
  }
  .toSeq

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
val eruVersion: String = "0.9.0+4-2c37ef6b"
val eruHttpVersion: String = "0.1.0-SNAPSHOT"
val saratiVersion: String = "0.2.2"
val rumilVersion: String = "0.3.0"
val valarVersion: String = "0.6.0"
val munitVersion: String = "1.2.3"

// ===== Local Project References =====
// Use absolute path so these resolve correctly even when loaded via ProjectRef from another build
val examplesDir: File = file("/home/hakim/examples")

val useLocalEruHttp: Boolean = (examplesDir / "eru-http" / "build.sbt").exists()
val useLocalSarati: Boolean = (examplesDir / "sarati" / "build.sbt").exists()
val useLocalRumil: Boolean = (examplesDir / "rumil" / "build.sbt").exists()

lazy val eruHttpCoreRef: Option[ProjectRef] =
  if (useLocalEruHttp) Some(ProjectRef(examplesDir / "eru-http", "coreJVM")) else None
lazy val eruHttpServerRef: Option[ProjectRef] =
  if (useLocalEruHttp) Some(ProjectRef(examplesDir / "eru-http", "server")) else None
lazy val saratiRef: Option[ProjectRef] =
  if (useLocalSarati) Some(ProjectRef(examplesDir / "sarati", "root")) else None
lazy val rumilParsersRef: Option[ProjectRef] =
  if (useLocalRumil) Some(ProjectRef(examplesDir / "rumil", "parsers")) else None

// ===== Modules =====

lazy val core = (project in file("melian-core"))
  .settings(
    name := "melian-core",
    scalacOptions ++= sharedScalacOptions,
    libraryDependencies ++= Seq(
      "org.scalameta" %% "munit" % munitVersion % Test
    ) ++ (
      if (useLocalEruHttp) Seq.empty
      else Seq("net.ghoula" % "eru-http-core_3" % eruHttpVersion)
    ),
    javaOptions ++= Seq("-XX:+UseZGC"),
    Test / fork := true
  )
  .configure(p =>
    eruHttpCoreRef match {
      case Some(ref) => p.dependsOn(ref)
      case None => p
    }
  )

lazy val router = (project in file("melian-router"))
  .settings(
    name := "melian-router",
    scalacOptions ++= sharedScalacOptions,
    libraryDependencies ++= Seq(
      "org.scalameta" %% "munit" % munitVersion % Test
    ) ++ (
      if (useLocalSarati) Seq.empty
      else Seq("net.ghoula" %% "sarati" % saratiVersion)
    ) ++ (
      if (useLocalRumil) Seq.empty
      else Seq("net.ghoula" %% "rumil-parsers" % rumilVersion)
    ) ++ Seq(
      "net.ghoula" % "valar-core_3" % valarVersion
    ),
    javaOptions ++= Seq("-XX:+UseZGC"),
    Test / fork := true
  )
  .dependsOn(core)
  .configure(p => {
    val withSarati = saratiRef.fold(p)(ref => p.dependsOn(ref))
    rumilParsersRef.fold(withSarati)(ref => withSarati.dependsOn(ref))
  })

lazy val openapi = (project in file("melian-openapi"))
  .settings(
    name := "melian-openapi",
    scalacOptions ++= sharedScalacOptions,
    libraryDependencies ++= Seq(
      "org.scalameta" %% "munit" % munitVersion % Test
    ) ++ (
      if (useLocalSarati) Seq.empty
      else Seq("net.ghoula" %% "sarati" % saratiVersion)
    ),
    javaOptions ++= Seq("-XX:+UseZGC"),
    Test / fork := true
  )
  .dependsOn(core)
  .configure(p => saratiRef.fold(p)(ref => p.dependsOn(ref)))

lazy val server = (project in file("melian-server"))
  .settings(
    name := "melian-server",
    scalacOptions ++= sharedScalacOptions,
    libraryDependencies ++= Seq(
      "org.scalameta" %% "munit" % munitVersion % Test
    ),
    javaOptions ++= Seq("-XX:+UseZGC"),
    Test / fork := true
  )
  .dependsOn(core, router)
  .configure(p =>
    eruHttpServerRef match {
      case Some(ref) => p.dependsOn(ref)
      case None => p
    }
  )

lazy val testKit = (project in file("melian-test"))
  .settings(
    name := "melian-test",
    scalacOptions ++= sharedScalacOptions,
    libraryDependencies ++= Seq(
      "org.scalameta" %% "munit" % munitVersion % Test
    ),
    javaOptions ++= Seq("-XX:+UseZGC"),
    Test / fork := true
  )
  .dependsOn(core, router)

lazy val root = (project in file("."))
  .settings(
    name := "melian",
    publish / skip := true
  )
  .aggregate(core, router, openapi, server, testKit)

// ===== Command Aliases =====
addCommandAlias("testAll", "test")
addCommandAlias("prepare", "scalafmtAll; scalafmtSbt; scalafixAll")
