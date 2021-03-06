import Dependencies._

import Shared._

import sbtbuildinfo.Plugin._

organization := "noootsab"

name := "spark-notebook"

scalaVersion := defaultScalaVersion

version in ThisBuild <<= (scalaVersion, sparkVersion, hadoopVersion) { (sc, sv, hv) => s"0.4.1-scala-$sc-spark-$sv-hadoop-$hv" }

maintainer := "Andy Petrella" //Docker

enablePlugins(UniversalPlugin)

enablePlugins(DockerPlugin)

dockerExposedPorts in Docker := Seq(9000, 9443) //Docker

dockerRepository := Some("andypetrella") //Docker

packageName in Docker := "spark-notebook"

ivyScala := ivyScala.value map { _.copy(overrideScalaVersion = true) }

parallelExecution in Test in ThisBuild := false

// these java options are for the forked test JVMs
javaOptions in ThisBuild ++= Seq("-Xmx512M", "-XX:MaxPermSize=128M")

resolvers in ThisBuild ++=  Seq(
                              Resolver.typesafeRepo("releases"),
                              Resolver.sonatypeRepo("releases"),
                              Resolver.typesafeIvyRepo("releases"),
                              Resolver.typesafeIvyRepo("snapshots"),
                              "cloudera" at "https://repository.cloudera.com/artifactory/cloudera-repos",
                              // docker
                              "softprops-maven" at "http://dl.bintray.com/content/softprops/maven"
                            )

EclipseKeys.skipParents in ThisBuild := false

compileOrder := CompileOrder.Mixed

publishMavenStyle := false

javacOptions ++= Seq("-Xlint:deprecation", "-g")

scalacOptions += "-deprecation"

scalacOptions ++= Seq("-Xmax-classfile-name", "100")

commands ++= Seq( distZips, distDebs, distAll, dockerPublishLocalAll, dockerPublishAll )

val ClasspathPattern = "declare -r app_classpath=\"(.*)\"\n".r

bashScriptDefines :=  bashScriptDefines.value.map {
                              case ClasspathPattern(classpath) => "declare -r app_classpath=\"${HADOOP_CONF_DIR}:" + classpath + "\"\n"
                              case _@entry => entry
                          }

//scriptClasspath += "${HADOOP_CONF_DIR}"

dependencyOverrides += "log4j" % "log4j" % "1.2.16"

dependencyOverrides += guava

enablePlugins(DebianPlugin)

sharedSettings

libraryDependencies ++= playDeps

libraryDependencies ++= Seq(
  akka,
  akkaRemote,
  akkaSlf4j,
  cache,
  commonsIO,
  // ↓ to fix java.lang.IllegalStateException: impossible to get artifacts when data has
  //   not been loaded. IvyNode = org.apache.commons#commons-exec;1.1
  //   encountered when using hadoop "2.0.0-cdh4.2.0"
  commonsExec,
  commonsCodec,
  //scala stuffs
  "org.scala-lang" % "scala-library" % defaultScalaVersion,
  "org.scala-lang" % "scala-reflect" % defaultScalaVersion,
  "org.scala-lang" % "scala-compiler" % defaultScalaVersion
)

lazy val sparkNotebook = project.in(file(".")).enablePlugins(play.PlayScala).enablePlugins(SbtWeb)
    .aggregate(subprocess, observable, common, spark, kernel)
    .dependsOn(subprocess, observable, common, spark, kernel)
    .settings(
      sharedSettings:_*
    )
    .settings(
      includeFilter in (Assets, LessKeys.less) := "*.less"
    )


lazy val subprocess =  project.in(file("modules/subprocess"))
                              .settings(
                                libraryDependencies ++= playDeps
                              )
                              .settings(
                                libraryDependencies ++= {
                                  Seq(
                                    akka,
                                    akkaRemote,
                                    akkaSlf4j,
                                    commonsIO,
                                    commonsExec,
                                    log4j
                                  )
                                }
                              )
                              .settings(
                                sharedSettings:_*
                              )
                              .settings(
                                sparkSettings:_*
                              )


lazy val observable = Project(id = "observable", base = file("modules/observable"))
                              .dependsOn(subprocess)
                              .settings(
                                libraryDependencies ++= Seq(
                                  akkaRemote,
                                  akkaSlf4j,
                                  slf4jLog4j,
                                  rxScala
                                )
                              )
                              .settings(
                                sharedSettings:_*
                              )

lazy val common = Project(id = "common", base = file("modules/common"))
                              .dependsOn(observable)
                              .settings(
                                libraryDependencies ++= Seq(
                                  akka,
                                  log4j,
                                  scalaZ
                                ),
                                libraryDependencies ++= sbtForDeps(scalaBinaryVersion.value, sbtVersion.value),
                                //addSbtPlugin("com.frugalmechanic" % "fm-sbt-s3-resolver" % "0.5.0"), // WARN ONLY 2.10 0.13 available !!!!
                                // plotting functionality
                                libraryDependencies ++= Seq(
                                  bokeh,
                                  wisp
                                )// ++ customJacksonScala
                              )
                              .settings(
                                sharedSettings:_*
                              )
                              .settings(
                                sparkSettings:_*
                              )
                              .settings(
                                buildInfoSettings:_*
                              )
                              .settings(
                                sourceGenerators in Compile <+= buildInfo,
                                buildInfoKeys := Seq[BuildInfoKey](version, scalaVersion, sparkVersion , hadoopVersion , jets3tVersion , jlineDef, sbtVersion),
                                buildInfoPackage := "notebook"
                              )


lazy val spark = Project(id = "spark", base = file("modules/spark"))
                              .dependsOn(common, subprocess, observable)
                              .settings(
                                libraryDependencies ++= Seq(
                                  akkaRemote,
                                  akkaSlf4j,
                                  slf4jLog4j,
                                  commonsIO
                                ),
                                libraryDependencies ++= Seq(
                                  jlineDef.value._1 % "jline" % jlineDef.value._2,
                                  "org.scala-lang" % "scala-compiler" % scalaVersion.value
                                ),
                                unmanagedSourceDirectories in Compile +=
                                  (sourceDirectory in Compile).value / ("scala_" + ((scalaBinaryVersion.value, sparkVersion.value) match {
                                    case (v, sv) if v startsWith "2.10" => "2.10"+"/spark-"+sv
                                    case (v, sv) if v startsWith "2.11" => "2.11"+"/spark-"+sv
                                    case (v, sv) => throw new IllegalArgumentException("Bad scala version: " + v)
                                  }))
                              )
                              .settings(
                                sharedSettings:_*
                              )
                              .settings(
                                sparkSettings:_*
                              )

lazy val kernel = Project(id = "kernel", base = file("modules/kernel"))
                              .dependsOn(common, subprocess, observable, spark)
                              .settings(
                                libraryDependencies ++= Seq(
                                  akkaRemote,
                                  akkaSlf4j,
                                  slf4jLog4j,
                                  commonsIO
                                )
                              )
                              .settings(
                                sharedSettings:_*
                              )