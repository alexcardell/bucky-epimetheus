Global / onChangedBuildSource := ReloadOnSourceChanges

val repo = "bucky-epimetheus"

ThisBuild / organization := "io.cardell"
ThisBuild / scmInfo := Some(
  ScmInfo(
    url(s"https://github.com/alexcardell/$repo"),
    s"scm:git@github.com:alexcardell/$repo.git"
  )
)
ThisBuild / licenses := List(
  "Apache-2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0")
)
ThisBuild / developers := List(
  Developer(
    "alexcardell",
    "Alex Cardell",
    "29524087+alexcardell@users.noreply.github.com",
    url("https://github.com/alexcardell")
  )
)
ThisBuild / homepage := Some(
  url(s"https://github.com/alexcardell/$repo")
)
ThisBuild / publishTo := sonatypePublishToBundle.value
ThisBuild / sonatypeCredentialHost := "s01.oss.sonatype.org"
ThisBuild / versionScheme := Some("early-semver")

lazy val scala2_12 = "2.12.15"
lazy val scala2_13 = "2.13.8"
// TODO cross publish
lazy val crossVersions = Seq(scala2_13)
ThisBuild / crossScalaVersions := crossVersions
ThisBuild / scalaVersion := scala2_13

ThisBuild / scalafixDependencies += "com.github.liancheng" %% "organize-imports" % "0.6.0"
ThisBuild / semanticdbEnabled := true
ThisBuild / semanticdbVersion := scalafixSemanticdb.revision

lazy val commonSettings = Seq(
  addCompilerPlugin(
    "org.typelevel" % "kind-projector" % "0.13.2" cross CrossVersion.full
  ),
  scalacOptions := Seq("-Wunused")
)

lazy val root = project
  .in(file("."))
  .settings(commonSettings: _*)
  .settings(
    name := s"$repo-root",
    publishArtifact := false,
    publish / skip := false
  )
  .aggregate(core, docs)

lazy val core = project
  .in(file("core"))
  .settings(commonSettings: _*)
  .settings(
    name := s"$repo",
    libraryDependencies ++= Seq(
      "com.itv" %% "bucky-core" % "3.1.0",
      "io.chrisdavenport" %% "epimetheus" % "0.5.0",
      "com.disneystreaming" %% "weaver-cats" % "0.8.0" % Test,
      "com.itv" %% "bucky-test" % "3.1.0" % Test,
      "io.circe" %% "circe-core" % "0.14.1" % Test,
      "io.circe" %% "circe-generic" % "0.14.1" % Test,
      "com.itv" %% "bucky-circe" % "3.1.0" % Test
    ),
    testFrameworks += new TestFramework("weaver.framework.CatsEffect")
  )

lazy val docs = project
  .in(file("mdoc-src"))
  .settings(commonSettings: _*)
  .dependsOn(core)
  .settings(
    publishArtifact := false,
    publish / skip := false
  )
  .settings(
    moduleName := s"$repo-docs",
    mdocVariables := Map(
      "VERSION" -> version.value
    )
  )
  .enablePlugins(MdocPlugin)

addCommandAlias("fix", "scalafmtAll;scalafmtSbt;scalafixAll")
addCommandAlias(
  "check",
  List("scalafmtCheckAll", "scalafmtSbtCheck", "scalafix --check")
    .mkString(";")
)
