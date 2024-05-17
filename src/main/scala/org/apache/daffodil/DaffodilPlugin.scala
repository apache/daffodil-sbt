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

  object autoImport {
    val daffodilPackageBinInfos = settingKey[Seq[(String, Option[String], Option[String])]](
      "Sequence of 3-tuple defining the main schema resource, optional root element, and optional name",
    )
    val daffodilPackageBinVersions = settingKey[Seq[String]](
      "Versions of daffodil to create saved parsers for",
    )
    val packageDaffodilBin = taskKey[Seq[File]](
      "Package daffodil saved parsers",
    )
    val daffodilVersion = settingKey[String](
      "Version of daffodil to add as a dependency",
    )
    val daffodilBuildsLayer = settingKey[Boolean](
      "Whether or not the project builds a layer",
    )
    val daffodilBuildsUDF = settingKey[Boolean](
      "Whether or not the project builds a user defined function",
    )
    val daffodilFlatLayout = settingKey[Boolean](
      "Whether or not to use a flat schema project layout that uses src/ and test/ root directories containing a mix of sources and resources",
    )
    val daffodilTdmlUsesPackageBin = settingKey[Boolean](
      "Whether or not TDML files use the saved parsers created by daffodilPackageBin",
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
     * Default Daffodil version
     */
    daffodilVersion := "3.7.0",

    /**
     * Disable uncommon features by default, schema projects must explicitly enable them to use
     */
    daffodilBuildsLayer := false,
    daffodilBuildsUDF := false,
    daffodilTdmlUsesPackageBin := false,

    /**
     * Add Daffodil and version specific test dependencies
     */
    libraryDependencies ++= {
      // Seq of 2-tuples, where each tuple is a Seq of version specifiers and a list of
      // dependencies to add if the daffodilVersion matches all of those specifiers. If the
      // version specifier Seq is empty, the associated dependencies are added regardless of
      // Daffodil version
      val versionedDeps = Seq(
        // always add Daffodil and junit test dependencies
        Nil -> Seq(
          "org.apache.daffodil" %% "daffodil-tdml-processor" % daffodilVersion.value % "test",
          "junit" % "junit" % "4.13.2" % "test",
          "com.github.sbt" % "junit-interface" % "0.13.2" % "test",
        ),
        // Add log4j with older versions of Daffodil to silence warnings about missing loggers
        Seq(">=3.2.0", "<=3.4.0") -> Seq(
          "org.apache.logging.log4j" % "log4j-core" % "2.20.0" % "test",
        ),
      )

      val dafVer = VersionNumber(daffodilVersion.value)
      val dependencies = versionedDeps
        .filter { case (vers, _) => vers.forall { v => SemanticSelector(v).matches(dafVer) } }
        .flatMap { case (_, deps) => deps }
      dependencies
    },

    /**
     * Add layer compile dependencies if enabled
     */
    libraryDependencies ++= {
      if (daffodilBuildsLayer.value) {
        Seq("org.apache.daffodil" %% "daffodil-runtime1-layers" % daffodilVersion.value)
      } else {
        Seq()
      }
    },

    /**
     * Add UDF compile dependencies if enabled
     */
    libraryDependencies ++= {
      if (daffodilBuildsUDF.value) {
        Seq("org.apache.daffodil" %% "daffodil-udf" % daffodilVersion.value)
      } else {
        Seq()
      }
    },

    /**
     * DFDL schemas are not scala version specific since they just contain resources, so we
     * disable crossPaths so that published jars do not contain a scala version. However, if a
     * project builds layers or UDFs, then we do need to enable crossPaths since those might be
     * implemented in Scala and so is version dependent. If they are implemented purely in Java,
     * projects can override this and change the setting to false if they don't want the scala
     * version in the jar name.
     */
    crossPaths := (daffodilBuildsLayer.value || daffodilBuildsUDF.value),

    /**
     * Enable verbose logging for junit tests
     */
    testOptions += Tests.Argument(TestFrameworks.JUnit, "-v"),

    /**
     * Disable the flat layout by default
     */
    daffodilFlatLayout := false,

    /**
     * Default to building no saved parsers and supporting no versions of daffodil
     */
    daffodilPackageBinInfos := Seq(),
    daffodilPackageBinVersions := Seq(),

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

    /**
    * If daffodilTdmlUsesPackageBin is true, we create a resource generator to build the saved
    * parsers and add them as a resource for the TDML files to find and use. Note that we use a
    * resourceGenerator since other methods make it difficult to convince IntelliJ to put the
    * files on the test classpath. See below Test/packageBin/mappings for related changes.
     */
    Test / resourceGenerators += Def.taskIf {
      if (daffodilTdmlUsesPackageBin.value) {

        if (!daffodilPackageBinVersions.value.contains(daffodilVersion.value)) {
          throw new MessageOnlyException(
            s"daffodilPackageBinVersions (${daffodilPackageBinVersions.value.mkString(", ")}) must contain daffodilVersion (${daffodilVersion.value}) if daffodilTdmlUsesPackageBin is true",
          )
        }

        // force creation of saved parsers, there isn't currently a way to build them for just
        // daffodilVersion
        val allSavedParsers = packageDaffodilBin.value

        // copy the saved parsers for the current daffodilVersion to the root of the
        // resourceManaged directory, and consider those our generated resources
        val destDir = (Test / resourceManaged).value
        val tdmlParserFiles = daffodilPackageBinInfos.value.map { case (_, _, optName) =>
          val sourceClassifier = classifierName(optName, daffodilVersion.value)
          val source = target.value / s"${name.value}-${version.value}-${sourceClassifier}.bin"
          val destClassifier = optName.map { "-" + _ }.getOrElse("")
          val dest = destDir / s"${name.value}${destClassifier}.bin"
          IO.copyFile(source, dest)
          dest
        }
        tdmlParserFiles
      } else {
        Seq()
      }
    }.taskValue,

    /**
     * The above resource generator creates saved parsers as test resources so that tests can
     * find them on the classpath. But this means the parsers will also be packaged in test
     * jars. Saved parsers are already published as artifacts, so there's no reason to also
     * include them in jars--remove them from the mapping that says which files to put in jars.
     */
    Test / packageBin / mappings := {
      val existingMappings = (Test / packageBin / mappings).value
      if (daffodilTdmlUsesPackageBin.value) {
        val tdmlParserNames = daffodilPackageBinInfos.value.map { case (_, _, optName) =>
          val destClassifier = optName.map { "-" + _ }.getOrElse("")
          s"${name.value}${destClassifier}.bin"
        }
        existingMappings.filterNot { case (_, name) => tdmlParserNames.contains(name) }
      } else {
        existingMappings
      }
    },
  ) ++
    inConfig(Compile)(flatLayoutSettings("src")) ++
    inConfig(Test)(flatLayoutSettings("test"))

  /**
   * If daffodilFlatLayout is true, returns settings to make a flat directory layout. All
   * sources and resources for a configuration are expected to be in the directory specified by
   * the "dir" parameter. Files that end in *.scala or *.java are treated as sources, and all
   * other files are treated as resources. This should be used inside a Compile or Test
   * configuration, like this:
   *
   *   inConfig(Compile)(flatLayoutSettings("src"))
   *   inConfig(Test)(flatLayoutSettings("test"))
   *
   * If daffodilFlatLayout is false, this returns each of the settings unchanged.
   */
  def flatLayoutSettings(dir: String) = Seq(
    unmanagedSourceDirectories := {
      if (!daffodilFlatLayout.value) unmanagedSourceDirectories.value
      else Seq(baseDirectory.value / dir)
    },
    unmanagedResourceDirectories := {
      if (!daffodilFlatLayout.value) unmanagedResourceDirectories.value
      else unmanagedSourceDirectories.value
    },
    unmanagedSources / includeFilter := {
      if (!daffodilFlatLayout.value) (unmanagedSources / includeFilter).value
      else "*.java" | "*.scala"
    },
    unmanagedResources / excludeFilter := {
      if (!daffodilFlatLayout.value) (unmanagedResources / excludeFilter).value
      else (unmanagedSources / includeFilter).value,
    },
  )

}
