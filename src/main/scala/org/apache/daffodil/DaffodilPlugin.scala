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
import scala.language.implicitConversions
import scala.util.Properties

import sbt.Keys._
import sbt._

object DaffodilPlugin extends AutoPlugin {

  object autoImport {
    val daffodilPackageBinInfos = settingKey[Seq[DaffodilBinInfo]](
      "Information used to create compiled parsers",
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

    /**
     * Class to define daffodilPackageBinInfos, auto-imported to simplify sbt configs
     */
    case class DaffodilBinInfo(
      schema: String,
      root: Option[String] = None,
      name: Option[String] = None,
      config: Option[File] = None,
    )

    /**
     * Provides backwards compatibility with the older versions of the plugin where
     * daffodilPackagBinInfos was a Seq of 3-tuples instead of a DaffodilBinInfo
     */
    implicit def threeTupleToDaffodilBinInfo(t: (String, Option[String], Option[String])) = {
      DaffodilBinInfo(
        schema = t._1,
        root = t._2,
        name = t._3,
      )
    }
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

  /**
   * Filter a Map based on a version and whether or not it matches SemanticSelectors. See the
   * SBT documenation for the syntax here:
   *
   * https://github.com/sbt/librarymanagement/blob/develop/core/src/main/contraband-scala/sbt/librarymanagement/SemanticSelector.scala
   *
   * For each mapping, if the SemanticSelector matches the given version, then the value of that
   * mapping is selected. All mapping values that are selected are added to a Seq and returned.
   * There is no implied order in the mapping and it is possible for multiple mappings to be
   * selected. If only one mapping should be selected, the SemanticSelector keys should be made
   * to be non-overlapping.
   */
  def filterVersions[T](version: String, mappings: Map[String, T]): Seq[T] = {
    val vn = VersionNumber(version)
    val filteredValues = mappings
      .filterKeys { semSel => SemanticSelector(semSel).matches(vn) }
      .values
      .toSeq
    filteredValues
  }

  override lazy val projectSettings: Seq[Setting[_]] = Seq(
    /**
     * Even though Daffodil defines a transitive dependency to a specific version of Scala, once
     * SBT sees that dependency it replaces it with a dependency to the value of the
     * scalaVersion setting, ignoring whatever version Daffodil depends on. Settings like
     * autoScalaLibrary and managedScalaInstance do not seem to change this behavior. So we
     * maintain a mapping of scalaVersions used for each Daffodil release and set the
     * scalaVersion setting based on the daffodilVersion setting. As long as schema projects do
     * not override this setting, it ensures they use the same Scala version that Daffodil was
     * released with. Schema projects can override this setting if they really need a specific
     * Scala version, but that should be rare. We also take into account the minimum scala
     * version supported by the current JDK.
     */
    scalaVersion := {
      val jdkMinScalaVersionMapping = Map(
        ">=23    " -> "2.12.20",
        ">=22 <23" -> "2.12.19",
        ">=21 <22" -> "2.12.18",
        ">=17 <21" -> "2.12.15",
        ">=11 <17" -> "2.12.4",
        "     <11" -> "2.12.0",
      )

      val daffodilScalaVersionMapping = Map(
        ">=3.7.0       " -> "2.12.19",
        ">=3.5.0 <3.7.0" -> "2.12.18",
        ">=3.4.0 <3.5.0" -> "2.12.17",
        ">=3.2.0 <3.4.0" -> "2.12.15",
        ">=3.1.0 <3.2.0" -> "2.12.13",
        "        <3.1.0" -> "2.12.11",
      )

      val dafScalaVersion =
        filterVersions(daffodilVersion.value, daffodilScalaVersionMapping).head
      val jdkScalaVersion =
        filterVersions(Properties.javaSpecVersion, jdkMinScalaVersionMapping).head
      if (SemanticSelector("<" + jdkScalaVersion).matches(VersionNumber(dafScalaVersion))) {
        jdkScalaVersion
      } else {
        dafScalaVersion
      }
    },

    /**
     * Default Daffodil version
     */
    daffodilVersion := "3.8.0",

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
      val versionedDeps = Map(
        // always add Daffodil and junit test dependencies
        ">=3.0.0" -> Seq(
          "org.apache.daffodil" %% "daffodil-tdml-processor" % daffodilVersion.value % "test",
          "junit" % "junit" % "4.13.2" % "test",
          "com.github.sbt" % "junit-interface" % "0.13.2" % "test",
        ),
        // Add log4j with older versions of Daffodil to silence warnings about missing loggers
        ">=3.2.0 <=3.4.0" -> Seq(
          "org.apache.logging.log4j" % "log4j-core" % "2.20.0" % "test",
        ),
      )
      val dependencies = filterVersions(daffodilVersion.value, versionedDeps).flatten
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
      daffodilPackageBinVersions.value.flatMap { binDaffodilVersion =>
        val cfg = ivyConfigName(binDaffodilVersion)
        val dafDep = "org.apache.daffodil" %% "daffodil-japi" % binDaffodilVersion % cfg
        // logging backends used to hide warnings about missing backends, Daffodil won't
        // actually output logs that we care about, so this doesn't really matter
        val logMappings = Map(
          ">=3.5.0" -> "org.slf4j" % "slf4j-nop" % "2.0.9" % cfg,
          "<3.5.0" -> "org.apache.logging.log4j" % "log4j-core" % "2.20.0" % cfg,
        )
        val logDep = filterVersions(binDaffodilVersion, logMappings).head
        Seq(dafDep, logDep)
      }.toSeq
    },

    /**
     * define the artifacts and the packageDaffodilXyzBin task that creates the artifacts
     */
    packageDaffodilBin / artifacts := {
      daffodilPackageBinVersions.value.flatMap { daffodilVersion =>
        daffodilPackageBinInfos.value.map { dbi =>
          // each artifact has the same name as the jar, in the "parser" type, "bin" extension,
          // and daffodil version specific classifier. If dbi.name is Some, it is prepended
          // to the classifier separated by a hyphen. Note that publishing as maven style will
          // only use the name, extension, and classifier
          val classifier = classifierName(dbi.name, daffodilVersion)
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
      val groupedClassifiers = daffodilPackageBinInfos.value.groupBy { _.name }
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

          daffodilPackageBinInfos.value.map { dbi =>
            val classifier = classifierName(dbi.name, daffodilVersion)
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
              dbi.schema,
              targetFile.toString,
              dbi.root.getOrElse(""),
              dbi.config.map { _.toString }.getOrElse(""),
            )

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
        val tdmlParserFiles = daffodilPackageBinInfos.value.map { dbi =>
          val sourceClassifier = classifierName(dbi.name, daffodilVersion.value)
          val source = target.value / s"${name.value}-${version.value}-${sourceClassifier}.bin"
          val destClassifier = dbi.name.map { "-" + _ }.getOrElse("")
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
        val tdmlParserNames = daffodilPackageBinInfos.value.map { dbi =>
          val destClassifier = dbi.name.map { "-" + _ }.getOrElse("")
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
