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

package org.apache.daffodil

import java.io.File

import sbt.Keys._
import sbt._

object DaffodilPlugin extends AutoPlugin {

  override def trigger = allRequirements

  object autoImport {
    val daffodilPackageBinInfos = settingKey[Seq[(String, Option[String], Option[String])]](
      "Sequence of 3-tuple defining the main schema resource, optional root element, and optional name",
    )
    val daffodilPackageBinVersions = settingKey[Set[String]](
      "Versions of daffodil to create saved parsers for",
    )
    val packageDaffodilBin = taskKey[Seq[File]](
      "Package daffodil saved parsers",
    )
  }

  import autoImport._

  /**
  * Generate a daffodil version specific ivy configuration string by removing everything
  * except for alphanumeric characters
   */
  def ivyConfigName(daffodilVersion: String): String = {
    "daffodil" + daffodilVersion.replaceAll("[^a-zA-Z0-9]", "")
  }

  /**
   * generate an artifact classifier name using the optional name and daffodil version
   */
  def classifierName(optName: Option[String], daffodilVersion: String): String = {
    val cfg = ivyConfigName(daffodilVersion)
    (optName.toSeq ++ Seq(cfg)).mkString("-")
  }

  override lazy val projectSettings: Seq[Setting[_]] = Seq(
    /**
     * Default to building no saved parsers and supporting no versions of daffodil
     */
    daffodilPackageBinInfos := Seq(),
    daffodilPackageBinVersions := Set(),

    /**
     * define and configure a custom Ivy configuration with dependencies to the Daffodil
     * versions we need, getting us easy access to the Daffodil jars and its dependencies
     */
    ivyConfigurations ++= daffodilPackageBinVersions.value.map { daffodilVersion =>
      val cfg = ivyConfigName(daffodilVersion)
      Configuration.of(cfg.capitalize, cfg)
    }.toSeq,
    libraryDependencies ++= {
      daffodilPackageBinVersions.value.flatMap { daffodilVersion =>
        val cfg = ivyConfigName(daffodilVersion)
        val dafDep = "org.apache.daffodil" %% "daffodil-japi" % daffodilVersion % cfg
        // logging backends used to hide warnings about missing backends, Daffodil won't
        // actually output logs that we care about, so this doesn't really matter
        val logDep = if (SemanticSelector(">=3.5.0").matches(VersionNumber(daffodilVersion))) {
          "org.slf4j" % "slf4j-nop" % "2.0.9" % cfg
        } else {
          "org.apache.logging.log4j" % "log4j-core" % "2.20.0" % cfg
        }
        Seq(dafDep, logDep)
      }.toSeq
    },

    /**
     * define the artifacts and the packageDaffodilXyzBin task that creates the artifacts
     */
    packageDaffodilBin / artifacts := {
      daffodilPackageBinVersions.value.flatMap { daffodilVersion =>
        daffodilPackageBinInfos.value.map { case (_, _, optName) =>
          // each artifact has the same name as the jar, in the "parser" type, "bin" extension,
          // and daffodil version specific classifier. If optName is provided, it is prepended
          // to the classifier separated by a hyphen. Note that publishing as maven style will
          // only use the name, extension, and classifier
          val classifier = classifierName(optName, daffodilVersion)
          Artifact(name.value, "parser", "bin", Some(classifier), Vector(), None)
        }
      }.toSeq
    },
    packageDaffodilBin := {
      val logger = streams.value.log

      // this plugin jar includes a forkable main class that does the actual schema compilation
      // and saving.
      val pluginJar =
        new File(this.getClass.getProtectionDomain.getCodeSource.getLocation.toURI)

      // get all dependencies and resources of this project
      val projectClasspath = (Compile / fullClasspath).value.files

      // need to dropRight to remove the dollar sign in the object name
      val mainClass = DaffodilSaver.getClass.getCanonicalName.dropRight(1)

      // schema compilation can be expensive, so we only want to fork and compile the schema if
      // any of the project classpath files change
      val filesToWatch = projectClasspath.flatMap { f =>
        if (f.isDirectory) PathFinder(f).allPaths.get else Seq(f)
      }.toSet

      // the name field is the only thing that makes saved parser artifacts unique. Ensure there
      // are no duplicates.
      val groupedClassifiers = daffodilPackageBinInfos.value.groupBy { case (_, _, optName) =>
        optName
      }
      val duplicates = groupedClassifiers.filter { case (k, v) => v.length > 1 }.keySet
      if (duplicates.size > 0) {
        val dupsStr = duplicates.mkString(", ")
        val msg = s"daffodilPackageBinInfos defines duplicate classifiers: $dupsStr"
        throw new MessageOnlyException(msg)
      }

      val ivyConfigs = ivyConfigurations.value
      val classpathTypesVal = (Compile / classpathTypes).value
      val updateVal = (Compile / update).value

      // FileFunction.cached creates a function that accepts files to watch. If any have
      // changed, cachedFun will call the function passing in the watched files to regenerate
      // the ouput. Note that we ignore the input watch files because they are slightly
      // differnent than what we need to pass to the forked java process, which is just things
      // that should be on the classpath, and not recurisvely everything inside the classpath
      val cachedDir = streams.value.cacheDirectory / "daffodilPackageBin"
      val cachedFun = FileFunction.cached(cachedDir) { (_: Set[File]) =>
        val targetFiles = daffodilPackageBinVersions.value.flatMap { daffodilVersion =>
          // get all the Daffodil jars and dependencies for the version of Daffodil associated with
          // this ivy config
          val cfg = ivyConfigs.find { _.name == ivyConfigName(daffodilVersion) }.get
          val daffodilJars = Classpaths.managedJars(cfg, classpathTypesVal, updateVal).files

          // Note that order matters here. The projectClasspath might have daffodil jars on it if
          // Daffodil is a compile dependency, which could be a different version from the version
          // of Daffodil we are compiling the schema for. So when we fork Java, daffodilJars must be
          // on the classpath before projectClasspath jars
          val classpathFiles = Seq(pluginJar) ++ daffodilJars ++ projectClasspath

          daffodilPackageBinInfos.value.map { case (mainSchema, optRoot, optName) =>
            val classifier = classifierName(optName, daffodilVersion)
            val targetFile = target.value / s"${name.value}-${version.value}-${classifier}.bin"

            // extract options out of DAFFODIL_JAVA_OPTS or JAVA_OPTS environment variables.
            // Note that this doesn't handle escaped spaces or quotes correctly, but that
            // hopefully shouldn't be needed for specifying java options
            val envArgs = None
              .orElse(sys.env.get("DAFFODIL_JAVA_OPTS"))
              .orElse(sys.env.get("JAVA_OPTS"))
              .map(_.split("\\s+").toSeq)
              .getOrElse(Seq.empty)

            val args = envArgs ++ Seq(
              "-classpath",
              classpathFiles.mkString(File.pathSeparator),
              mainClass,
              mainSchema,
              targetFile.toString,
            ) ++ optRoot.toSeq

            logger.info(s"compiling daffodil parser to ${targetFile} ...")

            val forkOpts = ForkOptions()
              .withOutputStrategy(Some(LoggedOutput(logger)))
            val ret = Fork.java(forkOpts, args)
            if (ret != 0) {
              throw new MessageOnlyException(s"failed to save daffodil parser ${classifier}")
            }
            targetFile
          }
        }
        targetFiles.toSet
      }

      val savedParsers = cachedFun(filesToWatch)
      savedParsers.toSeq
    },

    /**
     * These two settings tell sbt about the artifacts and the task that generates the artifacts
     * so it knows to generate and publish them when publish/publihLocal/publishM2 is run
     */
    artifacts ++= (packageDaffodilBin / artifacts).value,
    packagedArtifacts := {
      val arts = (packageDaffodilBin / artifacts).value
      val files = packageDaffodilBin.value

      // the artifacts and associated files are not necessarily in the same order. For each
      // artifact, we need to find the associated file (the one that ends with the same
      // classifier and extension) and update the packagedArtifacts setting with that pair
      val updatedPackagedArtifacts = arts.foldLeft(packagedArtifacts.value) { case (pa, art) =>
        val suffix = s"-${art.classifier.get}.${art.extension}"
        val file = files.find { _.getName.endsWith(suffix) }.get
        pa.updated(art, file)
      }
      updatedPackagedArtifacts
    },
  )

}
