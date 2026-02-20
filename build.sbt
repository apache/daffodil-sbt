/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

val commonSettings = Seq(
  organization := "org.apache.daffodil",
  version := IO.read((ThisBuild / baseDirectory).value / "VERSION").trim,
  scalacOptions ++= {
    scalaBinaryVersion.value match {
      case "2.12" | "2.13" =>
        Seq(
          "-Werror",
          "-Ywarn-unused:imports"
        )
      case "3" =>
        Seq(
          "-no-indent",
          "-Werror",
          "-Wunused:imports"
        )
    }
  },
  scmInfo := Some(
    ScmInfo(
      browseUrl = url("https://github.com/apache/daffodil-sbt"),
      connection = "scm:git:https://github.com/apache/daffodil-sbt"
    )
  ),
  licenses := Seq(License.Apache2),
  homepage := Some(url("https://daffodil.apache.org")),
  releaseNotesURL := Some(url(s"https://daffodil.apache.org/sbt/${version.value}/"))
)

lazy val plugin = (project in file("."))
  .settings(commonSettings)
  .settings(
    name := "sbt-daffodil",

    // SBT plugin settings
    scalaVersion := "2.12.19",
    crossSbtVersions := Seq("1.8.0"),
    scriptedLaunchOpts ++= Seq(
      "-Xmx1024M",
      "-Dplugin.version=" + version.value
    ),
    scriptedDependencies := {
      // scripted runs publishLocal for the plugin and its dependencies as part of the
      // scriptedDependencies task. But the utils subprojects aren't actually dependencies (so
      // won't be locally published). We still need the util jars locally published so the
      // scripted tests can find the jars at runtime, so we manually run publishLocal for each
      // of the utils subprojects as part of the scriptedDependencies task
      publishLocal.all(ScopeFilter(projects = inProjects(utils.projectRefs: _*))).value
      scriptedDependencies.value
    },
    Test / test := {
      // run all scripted tasks as part of testing
      (Compile / scripted).toTask("").value
      (Test / test).value
    },

    // Rat check settings
    ratExcludes := Seq(
      file(".git"),
      file("VERSION"),
      file("src/sbt-test/sbt-daffodil/versions-01/src/test/resources/com/example/input.txt")
    ),
    ratFailBinaries := true
  )
  .enablePlugins(SbtPlugin)
  .aggregate(utils.projectRefs: _*)

lazy val utils = (projectMatrix in file("utils"))
  .settings(commonSettings)
  .settings(
    name := "sbt-daffodil-utils"
  )
  .settings(
    javacOptions ++= {
      scalaBinaryVersion.value match {
        case "2.12" => Seq("-target", "8")
        case "2.13" => Seq("-target", "8")
        case "3" => Seq("-target", "17")
      }
    },
    scalacOptions ++= {
      scalaBinaryVersion.value match {
        case "2.12" => Seq(s"--target:jvm-8")
        case "2.13" => Seq(s"--release", "8")
        case "3" => Seq(s"--release", "17")
      }
    },
    libraryDependencies ++= {
      scalaBinaryVersion.value match {
        case "2.12" => {
          Seq(
            "org.apache.daffodil" %% "daffodil-japi" % "3.10.0" % "provided", // scala-steward:off
            "org.scala-lang.modules" %% "scala-collection-compat" % "2.14.0"
          )
        }
        case "2.13" => {
          Seq(
            "org.apache.daffodil" %% "daffodil-japi" % "3.11.0" % "provided" // scala-steward:off
          )
        }
        case "3" => {
          Seq(
            "org.apache.daffodil" %% "daffodil-core" % "4.1.0" % "provided"
          )
        }
      }
    },
    // Do not automatically add scala-library as a dependency to the sbt-daffodil-utils jar. We
    // expect scala-library to come from the provided Daffodil dependency. This is important
    // because in some cases newer versions of scala-library break serialization compatability
    // and the auto added version might be too new. When using sbt-daffodil-utils, the
    // sbt-daffodil plugin is careful about ensuring compatability
    autoScalaLibrary := false
  )
  .jvmPlatform(
    // We really only need Scala versions and not full ModuleIds, but using ModuleId.revision
    // makes it easier for scala-steward to detect and automatically update the versions when
    // new Scala patch versions are released.
    scalaVersions = Seq(
      ("org.scala-lang" % "scala-library" % "2.12.21").revision,
      ("org.scala-lang" % "scala-library" % "2.13.18").revision,
      ("org.scala-lang" % "scala3-library" % "3.3.7").revision
    )
  )
