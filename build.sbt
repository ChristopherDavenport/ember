lazy val root = project.in(file("."))
  .aggregate(core, examples)
  .settings(commonSettings)
  .settings(noPublish)

lazy val core = project.in(file("modules/core"))
    .settings(commonSettings)
    .settings(releaseSettings)
    .settings(
      name := projectName("core")
    )

lazy val examples = project.in(file("modules/examples"))
  .settings(commonSettings)
  .settings(noPublish)
  .dependsOn(core)
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
val kittensV = "1.0.0-RC3"
val catsEffectV = "0.10.1"
val mouseV = "0.16"
val shapelessV = "2.3.2"
val fs2V = "0.10.6"
val http4sV = "0.18.5"
val circeV = "0.9.3"
val doobieV = "0.5.1"
val pureConfigV = "0.9.1"
val refinedV = "0.8.7"

val specs2V = "4.0.3"
val disciplineV = "0.10.0"
val scShapelessV = "1.1.6"

val logbackClassicV = "1.2.3"


lazy val contributors = Seq(
  "ChristopherDavenport" -> "Christopher Davenport"
)

lazy val commonSettings = Seq(
  organization := "io.chrisdavenport",

  scalaVersion := "2.12.4",
  crossScalaVersions := Seq(scalaVersion.value, "2.11.12"),
  resolvers += "JitPack" at "https://jitpack.io",

  addCompilerPlugin("com.github.oleg-py" %% "better-monadic-for" % "0.2.2"),
  addCompilerPlugin("org.spire-math" % "kind-projector" % "0.9.7" cross CrossVersion.binary),

  libraryDependencies ++= Seq(
    "org.typelevel"               %% "cats-core"                  % catsV,
    "org.typelevel"               %% "cats-effect"                % catsEffectV,
    "co.fs2"                      %% "fs2-core"                   % fs2V,
    "co.fs2"                      %% "fs2-io"                     % fs2V,
    "co.fs2"                      %% "fs2-scodec"                 % fs2V,
    "org.http4s"                  %% "http4s-core"              % http4sV,
    // "org.http4s"                  %% "http4s-blaze-client"        % http4sV,

    "org.specs2"                  %% "specs2-core"                % specs2V       % Test,
    "org.specs2"                  %% "specs2-scalacheck"          % specs2V       % Test,
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
