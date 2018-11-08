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
      "com.spinoco"                 %% "fs2-crypto"                 % "0.4.0"
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

val catsV = "1.4.0"
val catsEffectV = "1.0.0"
val fs2V = "1.0.0"
val http4sV = "0.20.0-M2"
val circeV = "0.10.1"

val specs2V = "4.3.5"
val disciplineV = "0.10.0"
val scShapelessV = "1.1.6"

val logbackClassicV = "1.2.3"


lazy val contributors = Seq(
  "ChristopherDavenport" -> "Christopher Davenport"
)

lazy val commonSettings = Seq(
  organization := "io.chrisdavenport",

  scalaVersion := "2.12.7",
  crossScalaVersions := Seq(scalaVersion.value, "2.11.12"),
  scalacOptions += "-Yrangepos",

  addCompilerPlugin("com.olegpy" %% "better-monadic-for" % "0.2.4"),
  addCompilerPlugin("org.spire-math" % "kind-projector" % "0.9.8" cross CrossVersion.binary),

  libraryDependencies ++= Seq(
    "org.typelevel"               %% "cats-core"                  % catsV,
    "org.typelevel"               %% "cats-effect"                % catsEffectV,
    "co.fs2"                      %% "fs2-io"                     % fs2V,
    "org.http4s"                  %% "http4s-core"              % http4sV,


    "org.specs2"                  %% "specs2-core"                % specs2V       % Test,
    "org.specs2"                  %% "specs2-scalacheck"          % specs2V       % Test,
    "org.typelevel"               %% "cats-effect-laws"           % catsEffectV   % Test,
    "org.typelevel"               %% "discipline"                 % disciplineV   % Test,
    "com.github.alexarchambault"  %% "scalacheck-shapeless_1.13"  % scShapelessV  % Test
  )
) ++ releaseSettings

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
