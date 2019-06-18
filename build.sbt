lazy val root = project.in(file("."))
  .aggregate(core, server, client, examples)
  .settings(commonSettings)
  .settings(noPublish)

lazy val core = project.in(file("modules/core"))
    .settings(commonSettings)
    .settings(releaseSettings)
    .settings(
      name := projectName("core")
    )

lazy val server = project.in(file("modules/server"))
  .settings(commonSettings)
  .settings(releaseSettings)
  .dependsOn(core)
  .settings(
    name := projectName("server"),
    libraryDependencies ++= Seq(
      "org.http4s"                  %% "http4s-server"              % http4sV
    )
  )

lazy val client = project.in(file("modules/client"))
  .settings(commonSettings)
  .settings(releaseSettings)
  .dependsOn(core)
  .settings(
    name := projectName("client"),
    libraryDependencies ++= Seq(
      "org.http4s"                  %% "http4s-client"              % http4sV,
      "io.chrisdavenport"           %% "log4cats-slf4j"             % log4catsV,
      "io.chrisdavenport"           %% "keypool"                    % keypoolV,
      "com.spinoco"                 %% "fs2-crypto"                 % fs2CryptoV
    )
  )

lazy val examples = project.in(file("modules/examples"))
  .settings(commonSettings)
  .settings(noPublish)
  .dependsOn(core, server, client)
  .settings(
    name := projectName("examples"),
    libraryDependencies ++= Seq(
      "org.http4s"                  %% "http4s-circe"               % http4sV,
      "org.http4s"                  %% "http4s-dsl"                 % http4sV,
      "io.circe"                    %% "circe-core"                 % circeV,
      "io.circe"                    %% "circe-generic"              % circeV,
      "io.circe"                    %% "circe-parser"               % circeV,

      "ch.qos.logback"              % "logback-classic"             % logbackClassicV,
    )
  )

val catsV = "2.0.0-M4"
val catsEffectV = "2.0.0-M4"
val fs2V = "1.1.0-M1"
val http4sV = "0.21.0-M1"
val circeV = "0.12.0-M3"
val log4catsV = "0.4.0-M1"
val keypoolV = "0.2.0-M2"
val fs2CryptoV = "0.5.0-M1"

val specs2V = "4.5.1"

val logbackClassicV = "1.2.3"


lazy val contributors = Seq(
  "ChristopherDavenport" -> "Christopher Davenport"
)

lazy val commonSettings = Seq(
  organization := "io.chrisdavenport",

  scalaVersion := "2.12.8",
  crossScalaVersions := Seq("2.13.0", scalaVersion.value, "2.11.12"),
  scalacOptions --= removeFatalWarnings(scalaVersion.value),

  addCompilerPlugin("com.olegpy"    %% "better-monadic-for" % "0.3.0"),
  addCompilerPlugin("org.typelevel" %  "kind-projector" % "0.10.3" cross CrossVersion.binary),

  libraryDependencies ++= Seq(
    "org.typelevel"               %% "cats-core"                  % catsV,
    "org.typelevel"               %% "cats-effect"                % catsEffectV,
    "co.fs2"                      %% "fs2-io"                     % fs2V,
    "org.http4s"                  %% "http4s-core"                % http4sV,

    "org.specs2"                  %% "specs2-core"                % specs2V       % Test,
    "org.specs2"                  %% "specs2-scalacheck"          % specs2V       % Test,
    "org.typelevel"               %% "cats-effect-laws"           % catsEffectV   % Test,
  )
) ++ releaseSettings 

def removeFatalWarnings(scalaVersion: String) = 
  if (priorTo2_13(scalaVersion)) Seq() else Seq("-Xfatal-warnings")

def priorTo2_13(scalaVersion: String): Boolean =
  CrossVersion.partialVersion(scalaVersion) match {
    case Some((2, minor)) if minor < 13 => true
    case _                              => false
}

lazy val releaseSettings = {
  import ReleaseTransformations._
  Seq(
    releaseCrossBuild := true,
    releaseProcess := Seq[ReleaseStep](
      checkSnapshotDependencies,
      inquireVersions,
      runClean,
      runTest,
      setReleaseVersion,
      commitReleaseVersion,
      tagRelease,
      // For non cross-build projects, use releaseStepCommand("publishSigned")
      releaseStepCommandAndRemaining("+publishSigned"),
      setNextVersion,
      commitNextVersion,
      releaseStepCommand("sonatypeReleaseAll"),
      pushChanges
    ),
    publishTo := {
      val nexus = "https://oss.sonatype.org/"
      if (isSnapshot.value)
        Some("snapshots" at nexus + "content/repositories/snapshots")
      else
        Some("releases" at nexus + "service/local/staging/deploy/maven2")
    },
    credentials ++= (
      for {
        username <- Option(System.getenv().get("SONATYPE_USERNAME"))
        password <- Option(System.getenv().get("SONATYPE_PASSWORD"))
      } yield
        Credentials(
          "Sonatype Nexus Repository Manager",
          "oss.sonatype.org",
          username,
          password
        )
    ).toSeq,
    publishArtifact in Test := false,
    releasePublishArtifactsAction := PgpKeys.publishSigned.value,
    scmInfo := Some(
      ScmInfo(
        url("https://github.com/ChristopherDavenport/ember"),
        "git@github.com:ChristopherDavenport/ember.git"
      )
    ),
    homepage := Some(url("https://github.com/ChristopherDavenport/ember")),
    licenses += ("MIT", url("http://opensource.org/licenses/MIT")),
    publishMavenStyle := true,
    pomIncludeRepository := { _ =>
      false
    },
    pomExtra := {
      <developers>
        {for ((username, name) <- contributors) yield
        <developer>
          <id>{username}</id>
          <name>{name}</name>
          <url>http://github.com/{username}</url>
        </developer>
        }
      </developers>
    }
  )
}

lazy val noPublish = {
  import com.typesafe.sbt.pgp.PgpKeys.publishSigned
  Seq(
    publish := {},
    publishLocal := {},
    publishSigned := {},
    publishArtifact := false
  )
}

def projectName(name: String): String = s"ember-$name" 
