import de.heikoseeberger.sbtheader.FileType
import play.twirl.sbt.Import.TwirlKeys

enablePlugins(PlayScala, AutomateHeaderPlugin)

name := "cloudbowl"

scalaVersion := "2.13.1"

resolvers += Resolver.mavenLocal

libraryDependencies ++= Seq(
  guice,
  ws,
  filters,

  "org.webjars"            %% "webjars-play"                    % "2.8.0",

  "com.typesafe.akka"      %% "akka-stream-kafka"               % "2.0.1",
  "com.typesafe.akka"      %% "akka-stream-contrib"             % "0.11",
  "com.lihaoyi"            %% "upickle"                         % "0.9.5",

  "com.dimafeng"           %% "testcontainers-scala-kafka"      % "0.34.3",
  "org.scalatestplus.play" %% "scalatestplus-play"              % "5.0.0" % "test"
)

scalacOptions ++= Seq(
  "-unchecked",
  "-deprecation",
  "-explaintypes",
  "-feature",
  "-Xcheckinit",
  //"-Xlog-implicits",
  "-Xfatal-warnings",
  "-Xlint:adapted-args",
  "-Xlint:constant",
  "-Xlint:delayedinit-select",
  "-Xlint:doc-detached",
  "-Xlint:inaccessible",
  "-Xlint:infer-any",
  "-Xlint:nullary-override",
  "-Xlint:nullary-unit",
  "-Xlint:option-implicit",
  "-Xlint:package-object-classes",
  "-Xlint:poly-implicit-overload",
  "-Xlint:private-shadow",
  "-Xlint:stars-align",
  "-Xlint:type-parameter-shadow",
  "-Ywarn-dead-code",
  "-Ywarn-extra-implicit",
  "-Ywarn-numeric-widen",
  "-Ywarn-unused:implicits",
  "-Ywarn-unused:locals",
  //"-Ywarn-unused:params", // disabled because Play routes needs a param to match a path
  "-Ywarn-unused:patvars",
  //"-Ywarn-unused:privates", // disabled because Play routes has an unused private val
)

pipelineStages := Seq(digest, gzip)

Assets / WebKeys.webJars := Seq.empty[File]

Global / cancelable := false

// license header stuff
organizationName := "Google LLC"
startYear := Some(2019)
licenses += "Apache-2.0" -> url("https://www.apache.org/licenses/LICENSE-2.0.txt")
headerMappings ++= Map(
  FileType("html") -> HeaderCommentStyle.twirlStyleBlockComment,
  FileType("js") -> HeaderCommentStyle.cStyleBlockComment,
  FileType("css") -> HeaderCommentStyle.cStyleBlockComment,
)
Compile / headerSources ++= (Compile / TwirlKeys.compileTemplates / sources).value
Compile / headerSources ++= (Assets / sources).value


// Override logging in test
Test / testOptions += Tests.Argument("-oDF")

run / fork := true

run / connectInput := true
