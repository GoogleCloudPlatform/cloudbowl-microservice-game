import de.heikoseeberger.sbtheader.FileType
import play.twirl.sbt.Import.TwirlKeys

enablePlugins(PlayScala, AutomateHeaderPlugin)

name := "cloudpit"

scalaVersion := "2.12.10"

resolvers += Resolver.mavenLocal

libraryDependencies ++= Seq(
  guice,
  ws,
  filters,
  jdbc,
  evolutions,

  "org.postgresql"         %  "postgresql"                      % "42.1.4",

  "io.getquill"            %% "quill-async-postgres"            % "3.5.0",

  "org.webjars"            %% "webjars-play"                    % "2.8.0",

  "org.apache.kafka"       %% "kafka-streams-scala"             % "2.4.0",

  "com.dimafeng"           %% "testcontainers-scala-postgresql" % "0.34.1",
  "com.dimafeng"           %% "testcontainers-scala-kafka"      % "0.34.3",
  "org.scalatestplus.play" %% "scalatestplus-play"              % "5.0.0" % "test"
)

scalacOptions ++= Seq(
  "-unchecked",
  "-deprecation",
  "-explaintypes",
  "-feature",
  "-Xcheckinit",
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

WebKeys.webJars in Assets := Seq.empty[File]

Global / cancelable := false

// license header stuff
organizationName := "Google LLC"
startYear := Some(2019)
licenses += "Apache-2.0" -> url("https://www.apache.org/licenses/LICENSE-2.0.txt")
headerMappings += FileType("html") -> HeaderCommentStyle.twirlStyleBlockComment
headerSources.in(Compile) ++= sources.in(Compile, TwirlKeys.compileTemplates).value


// Override logging in test
testOptions in Test += Tests.Argument("-oDF")

fork in runMain := true

connectInput in runMain := true
