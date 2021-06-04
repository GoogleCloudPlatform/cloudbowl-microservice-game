import de.heikoseeberger.sbtheader.FileType
import play.twirl.sbt.Import.TwirlKeys

enablePlugins(PlayScala, AutomateHeaderPlugin)

name := "cloudbowl"

scalaVersion := "2.13.6"

libraryDependencies ++= Seq(
  guice                                                                    ,
  ws                                                                       ,
  filters                                                                  ,

  "org.webjars"            %% "webjars-play"               % "2.8.0-1"     ,
  "org.webjars"            %  "bootstrap"                  % "4.6.0-1"     ,

  "com.typesafe.akka"      %% "akka-stream-kafka"          % "2.0.7"       ,
  "com.lihaoyi"            %% "upickle"                    % "1.3.6"       ,

  "com.pauldijou"          %% "jwt-core"                   % "5.0.0"       ,

  "com.dimafeng"           %% "testcontainers-scala-kafka" % "0.39.3"      ,
  "org.scalatestplus.play" %% "scalatestplus-play"         % "5.1.0" % Test,
)

scalacOptions ++= Seq(
  "-unchecked",
  "-deprecation",
  "-explaintypes",
  "-feature",
  "-Wconf:any:error",
  "-Xcheckinit",
  //"-Xlog-implicits",
  "-Xfatal-warnings",
  "-Xlint:adapted-args",
  "-Xlint:constant",
  "-Xlint:delayedinit-select",
  "-Xlint:doc-detached",
  "-Xlint:inaccessible",
  "-Xlint:infer-any",
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
  //"-Ywarn-unused:implicits",
  //"-Ywarn-unused:locals",
  //"-Ywarn-unused:params", // disabled because Play routes needs a param to match a path
  "-Ywarn-unused:patvars",
  //"-Ywarn-unused:privates", // disabled because Play routes has an unused private val
)

pipelineStages := Seq(digest, gzip)

Assets / WebKeys.webJars := Seq.empty[File]

Global / cancelable := false

// license header stuff
organizationName := "Google LLC"
startYear := Some(2020)
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
