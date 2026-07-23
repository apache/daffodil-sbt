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

import sbt._

// Scala versions to use for each supported Daffodil version range. Defined here in project/
// so scala-steward can detect and update them. A sourceGenerators task in build.sbt embeds
// these version strings into the compiled plugin.
//
// We use full ModuleIds rather than plain strings so scala-steward can detect and update the
// versions. The .revision call extracts just the version string at build time.
//
// If we update one of the below scala-library versions, we should verify that official
// Daffodil releases using older versions of scala-library can still reload saved parsers built
// with this newer scalaVersion (see the comment in DaffodilPlugin.scala for details). See the
// versions-01 scripted test for how to manually verify this.
object DaffodilScalaVersions {
  val scala3 = ("org.scala-lang" % "scala3-library" % "3.3.7").revision
  // scala-steward:off
  val scala213Pinned = ("org.scala-lang" % "scala-library" % "2.13.16").revision
  // scala-steward:on
  val scala212 = ("org.scala-lang" % "scala-library" % "2.12.21").revision
}
