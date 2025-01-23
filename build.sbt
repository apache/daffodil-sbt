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

name := "sbt-daffodil"

organization := "org.apache.daffodil"

version := "1.3.0"

scalaVersion := "2.12.19"

scalacOptions ++= Seq(
  "-Ywarn-unused:imports"
)

scmInfo := Some(
  ScmInfo(
    browseUrl = url("https://github.com/apache/daffodil-sbt"),
    connection = "scm:git:https://github.com/apache/daffodil-sbt"
  )
)

licenses := Seq(License.Apache2)

homepage := Some(url("https://daffodil.apache.org"))

releaseNotesURL := Some(url(s"https://daffodil.apache.org/sbt/${version.value}/"))

// SBT Plugin settings

enablePlugins(SbtPlugin)

crossSbtVersions := Seq("1.8.0")

scriptedLaunchOpts ++= Seq(
  "-Xmx1024M",
  "-Dplugin.version=" + version.value
)

// Rat check settings

ratExcludes := Seq(
  file(".git")
)

ratFailBinaries := true

Test / test := {
  // run all scripted tasks as part of testing
  (Compile / scripted).toTask("").value
  (Test / test).value
}
