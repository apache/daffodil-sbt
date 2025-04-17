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
import sbt.internal.CommandStrings.ExportStream

object DaffodilPlugin extends AutoPlugin {

  object autoImport {
    val daffodilPackageBinInfos = settingKey[Seq[DaffodilBinInfo]](
      "Information used to create compiled parsers"
    )
    val daffodilPackageBinVersions = settingKey[Seq[String]](
      "Versions of daffodil to create saved parsers for"
    )
    val packageDaffodilBin = taskKey[Seq[File]](
      "Package daffodil saved parsers"
    )
    val daffodilVersion = settingKey[String](
      "Version of daffodil to add as a dependency"
    )
    val daffodilBuildsCharset = settingKey[Boolean](
      "Whether or not the project builds a charset"
    )
    val daffodilBuildsLayer = settingKey[Boolean](
      "Whether or not the project builds a layer"
    )
    val daffodilBuildsUDF = settingKey[Boolean](
      "Whether or not the project builds a user defined function"
    )
    val daffodilFlatLayout = settingKey[Boolean](
      "Whether or not to use a flat schema project layout that uses src/ and test/ root directories containing a mix of sources and resources"
    )
    val daffodilTdmlUsesPackageBin = settingKey[Boolean](
      "Whether or not TDML files use the saved parsers created by daffodilPackageBin"
    )

    /**
     * Class to define daffodilPackageBinInfos, auto-imported to simplify sbt configs
     */
    case class DaffodilBinInfo(
      schema: String,
      root: Option[String] = None,
      name: Option[String] = None,
      config: Option[File] = None
    )

    /**
     * Provides backwards compatibility with the older versions of the plugin where
     * daffodilPackagBinInfos was a Seq of 3-tuples instead of a DaffodilBinInfo
     */
    implicit def threeTupleToDaffodilBinInfo(t: (String, Option[String], Option[String])) = {
      DaffodilBinInfo(
        schema = t._1,
        root = t._2,
        name = t._3
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
     * version supported by the current JDK, and will override with a newer version of scala if
     * required by the JDK.
     */
    scalaVersion := {
      val jdkMinScala212VersionMapping = Map(
        ">=23    " -> "2.12.20",
        ">=22 <23" -> "2.12.19",
        ">=21 <22" -> "2.12.18",
        ">=17 <21" -> "2.12.15",
        ">=11 <17" -> "2.12.4",
        "     <11" -> "2.12.0"
      )
      val jdkMinScala213VersionMapping = Map(
        ">=23    " -> "2.13.15",
        ">=22 <23" -> "2.13.13",
        ">=21 <22" -> "2.13.11",
        ">=17 <21" -> "2.13.6",
        ">=11 <17" -> "2.13.0",
        "     <11" -> "2.13.0"
      )

      val daffodilScalaVersionMapping = Map(
        ">=4.0.0       " -> "2.13.16",
        ">=3.9.0 <4.0.0" -> "2.12.20",
        ">=3.7.0 <3.9.0" -> "2.12.19",
        ">=3.5.0 <3.7.0" -> "2.12.18",
        ">=3.4.0 <3.5.0" -> "2.12.17",
        ">=3.2.0 <3.4.0" -> "2.12.15",
        ">=3.1.0 <3.2.0" -> "2.12.13",
        "        <3.1.0" -> "2.12.11"
      )

      val dafScalaVersion =
        filterVersions(daffodilVersion.value, daffodilScalaVersionMapping).head
      val jdkScalaVersion =
        if (SemanticSelector("<4.0.0").matches(VersionNumber(daffodilVersion.value))) {
          filterVersions(Properties.javaSpecVersion, jdkMinScala212VersionMapping).head
        } else {
          filterVersions(Properties.javaSpecVersion, jdkMinScala213VersionMapping).head
        }

      if (SemanticSelector("<" + jdkScalaVersion).matches(VersionNumber(dafScalaVersion))) {
        jdkScalaVersion
      } else {
        dafScalaVersion
      }
    },

    /**
     * Default Daffodil version
     */
    daffodilVersion := "3.10.0",

    /**
     * Disable uncommon features by default, schema projects must explicitly enable them to use
     */
    daffodilBuildsCharset := false,
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
        // For daffodil 3.10.0 or newer, depend on daffodil-tdml-junit. This transitively pulls
        // in daffodil-tdml-processor and gives access to the compact tdml runner API
        ">=3.10.0" -> Seq(
          "org.apache.daffodil" %% "daffodil-tdml-junit" % daffodilVersion.value % "test"
        ),
        // For older versions where daffodil-tdml-junit isn't available, depend on
        // daffodil-tdml-processor plus an *intransitive* dependency to the 3.10.0 version of
        // daffodil-tdml-junit. The daffodil-tdml-junit library uses an API that works with
        // daffodil-tdml-processor 3.2.0 or newer. Note that this should be pinned to 3.10.0,
        // since future versions of daffodil-tdml-junit might depend on a specific versions of
        // daffodil-tdml-processor
        ">=3.2.0 <3.10.0" -> Seq(
          "org.apache.daffodil" %% "daffodil-tdml-processor" % daffodilVersion.value % "test",
          ("org.apache.daffodil" %% "daffodil-tdml-junit" % "3.10.0" % "test").intransitive()
        ),
        // The TDML Runner API that daffodil-tdml-junit-3.10.0 uses was added in Daffodil 3.2.0.
        // So the new compact JUnit API cannot be used with 3.1.0 or older. For these projects,
        // just add daffodil-tdml-processor so they can at least use the old TDML Runner API
        ">=3.0.0 <3.2.0" -> Seq(
          "org.apache.daffodil" %% "daffodil-tdml-processor" % daffodilVersion.value % "test"
        ),
        // junit dependencies
        ">=3.0.0" -> Seq(
          "junit" % "junit" % "4.13.2" % "test",
          "com.github.sbt" % "junit-interface" % "0.13.2" % "test"
        ),
        // Add log4j with older versions of Daffodil to silence warnings about missing loggers
        ">=3.2.0 <=3.4.0" -> Seq(
          "org.apache.logging.log4j" % "log4j-core" % "2.20.0" % "test"
        )
      )
      val dependencies = filterVersions(daffodilVersion.value, versionedDeps).flatten
      dependencies
    },

    /**
     * Add Charset compile dependencies if enabled
     */
    libraryDependencies ++= {
      if (daffodilBuildsCharset.value) {
        Seq("org.apache.daffodil" %% "daffodil-io" % daffodilVersion.value % "provided")
      } else {
        Seq()
      }
    },

    /**
     * Add layer compile dependencies if enabled
     */
    libraryDependencies ++= {
      if (daffodilBuildsLayer.value) {
        Seq(
          "org.apache.daffodil" %% "daffodil-runtime1-layers" % daffodilVersion.value % "provided"
        )
      } else {
        Seq()
      }
    },

    /**
     * Add UDF compile dependencies if enabled
     */
    libraryDependencies ++= {
      if (daffodilBuildsUDF.value) {
        Seq("org.apache.daffodil" %% "daffodil-udf" % daffodilVersion.value % "provided")
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
    crossPaths := (daffodilBuildsCharset.value || daffodilBuildsLayer.value || daffodilBuildsUDF.value),

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
        // the daffodil-japi dependency must ignore the scalaVersion setting and instead use
        // the specific version of scala used for the binDaffodilVersion. We can do this by
        // defining the dependency with a "constant" cross version
        val crossVersion =
          if (SemanticSelector("<4.0.0").matches(VersionNumber(binDaffodilVersion))) {
            CrossVersion.constant("2.12")
          } else {
            CrossVersion.constant("2.13")
          }
        val dafDep = ("org.apache.daffodil" % "daffodil-japi" % binDaffodilVersion % cfg)
          .withCrossVersion(crossVersion)
        // Add logging backends used when packageDaffodilBin outputs log messages
        val logMappings = Map(
          ">=3.5.0" -> "org.slf4j" % "slf4j-simple" % "2.0.9" % cfg,
          "<3.5.0" -> "org.apache.logging.log4j" % "log4j-core" % "2.20.0" % cfg
        )
        val logDep = filterVersions(binDaffodilVersion, logMappings).head
        Seq(dafDep, logDep)
      }.toSeq
    },

    /**
     * define the artifacts, products, and the packageDaffodilBin task that creates the artifacts/products
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
    packageDaffodilBin / products := {
      val logger = streams.value.log
      val exporter = (packageDaffodilBin / streams).value.text(ExportStream)

      // options to provide to the forked JVM process used to save a processor
      val jvmArgs = (packageDaffodilBin / javaOptions).value

      // this plugin jar includes a forkable main class that does the actual schema compilation
      // and saving.
      val pluginJar =
        new File(this.getClass.getProtectionDomain.getCodeSource.getLocation.toURI)

      // get all dependencies and resources of this project
      val projectClasspath = (Compile / fullClasspath).value.files

      val mainClass = classOf[DaffodilSaver].getCanonicalName

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
        val msg = s"daffodilPackageBinInfos defines duplicate name parameters: $dupsStr"
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
            val targetName = s"${name.value}-${version.value}-${classifier}.bin"
            val targetFile = target.value / targetName

            if (!dbi.schema.startsWith("/")) {
              throw new MessageOnlyException(
                s"daffodilPackageBinInfos schema must be a resource path that starts with '/':  ${dbi.schema}"
              )
            }

            // the DaffodilSaver needs to know which version of Daffodil we are using to create
            // a saved processor, since some versions of Daffodil have different APIs and it
            // must use the correct one using reflection. The DaffodilSaver is forked without
            // SBT libraries on the classpath, so it can't easily use the SemanticVersion to
            // compare versions. So we map each Daffodil version to an "internal API" version,
            // which is just a simple number that is easier to use in the Saver and signify
            // where API functions to use. This is the mapping for which Daffodil API should be
            // used for a particular "internal API"
            val daffodilInternalApiVersionMapping = Map(
              ">3.8.0" -> "2",
              "<=3.8.0" -> "1"
            )
            val internalApiVersion =
              filterVersions(daffodilVersion, daffodilInternalApiVersionMapping).head

            val args = jvmArgs ++ Seq(
              "-classpath",
              classpathFiles.mkString(File.pathSeparator),
              mainClass,
              internalApiVersion,
              dbi.schema,
              targetFile.toString,
              dbi.root.getOrElse(""),
              dbi.config.map { _.toString }.getOrElse("")
            )

            // SBT has the concept of an "export stream"--this is a stream that an SBT task can
            // write to to indicate its equivalent command line program and options. This is
            // useful for debugging, allowing one to manually run an SBT task in a CLI. To
            // support this for packageDaffodilBin, we just need to get the exporter stream
            // above and write the equivalent command line to that stream. For a user to see
            // this stream, they can run "sbt 'export packageDaffodilBin'". Note that this wraps
            // the arguments in singles quotes to support arguments that might have spaces, be
            // empty, or have special characters. Also note that the arguments contain absolute
            // paths and platform specific classpath separator, so the resulting command likely
            // only works on the system where it is generated.
            exporter.println("java " + args.map(arg => s"'$arg'").mkString(" "))

            logger.info(s"compiling daffodil parser to ${targetFile} ...")

            val forkOpts = ForkOptions()
              .withOutputStrategy(Some(LoggedOutput(logger)))
            val ret = Fork.java(forkOpts, args)
            if (ret != 0) {
              throw new MessageOnlyException(s"failed to save daffodil parser: $targetName")
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
     * SBT ignores the packageDaffodilBin / logLevel setting, so we use it as the logLevel to
     * provide to daffodil when saving a parser. We default to warn because Daffodils "info" is
     * usually too verbose
     */
    packageDaffodilBin / logLevel := Level.Warn,

    /**
     * JVM options used for the forked process to build saved parsers
     *
     * Defaults to just setting various system properties to configure loggers that might be
     * used by different daffodil versions
     */
    packageDaffodilBin / javaOptions := Seq(
      s"-Dorg.slf4j.simpleLogger.logFile=System.out",
      s"-Dorg.slf4j.simpleLogger.defaultLogLevel=${(packageDaffodilBin / logLevel).value}",
      s"-Dorg.apache.logging.log4j.level=${(packageDaffodilBin / logLevel).value}"
    ),
    packageDaffodilBin := {
      val logger = streams.value.log

      val prods = (packageDaffodilBin / products).value
      if (prods.length == 0) {
        // only warn if packageDaffodilBin is expliclty run and no artifacts are generated
        // because one of the bin infos settings is empty. Any tasks that need this list of save
        // parser files but do not want this warning should access
        // (packageDaffodilBin / products).value instead of packageDaffodilBin.value
        logger.warn(
          "no saved parsers created--one or both of daffodilPackageBinInfos and daffodilPackageBinVersions are empty"
        )
      }
      prods
    },

    /**
     * These two settings tell sbt about the artifacts and the task that generates the artifacts
     * so it knows to generate and publish them when publish/publihLocal/publishM2 is run
     */
    artifacts ++= {
      if ((packageDaffodilBin / publishArtifact).value) {
        (packageDaffodilBin / artifacts).value
      } else {
        Seq()
      }
    },
    packagedArtifacts := {
      if ((packageDaffodilBin / publishArtifact).value) {
        val arts = (packageDaffodilBin / artifacts).value
        val files = (packageDaffodilBin / products).value

        // the artifacts and associated files are not necessarily in the same order. For each
        // artifact, we need to find the associated file (the one that ends with the same
        // classifier and extension) and update the packagedArtifacts setting with that pair
        val updatedPackagedArtifacts =
          arts.foldLeft(packagedArtifacts.value) { case (pa, art) =>
            val suffix = s"-${art.classifier.get}.${art.extension}"
            val file = files.find { _.getName.endsWith(suffix) }.get
            pa.updated(art, file)
          }
        updatedPackagedArtifacts
      } else {
        packagedArtifacts.value
      }
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
            s"daffodilPackageBinVersions (${daffodilPackageBinVersions.value.mkString(", ")}) must contain daffodilVersion (${daffodilVersion.value}) if daffodilTdmlUsesPackageBin is true"
          )
        }

        // force creation of saved parsers, there isn't currently a way to build them for just
        // daffodilVersion
        val allSavedParsers = (packageDaffodilBin / products).value

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
    }
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
      else {
        nonFlatWarning(Keys.sLog.value, unmanagedSourceDirectories.value)
        Seq(baseDirectory.value / dir)
      }
    },
    unmanagedResourceDirectories := {
      if (!daffodilFlatLayout.value) unmanagedResourceDirectories.value
      else {
        nonFlatWarning(Keys.sLog.value, unmanagedResourceDirectories.value)
        unmanagedSourceDirectories.value
      }
    },
    unmanagedSources / includeFilter := {
      if (!daffodilFlatLayout.value) (unmanagedSources / includeFilter).value
      else "*.java" | "*.scala"
    },
    unmanagedResources / excludeFilter := {
      if (!daffodilFlatLayout.value) (unmanagedResources / excludeFilter).value
      else (unmanagedSources / includeFilter).value
    }
  )

  /**
   * Before changing the unmanagedSource/ResourceDirectories settings to be flat, we should call
   * this function passing in the current non-flat setting value. This will detect if any of the
   * directories in a non-flat layout exist, and if so warn. This helps to avoid cases where
   * daffodilFlatLayout is true but the layout isn't actually flat.
   */
  private def nonFlatWarning(logger: Logger, dirsThatShouldNotExist: Seq[File]): Unit = {
    dirsThatShouldNotExist.filter(_.exists).foreach { dir =>
      logger.warn("daffodilFlatLayout is true, but the layout does not look flat: " + dir)
    }
  }

}
