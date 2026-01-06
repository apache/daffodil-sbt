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
import sbt.librarymanagement.Artifact.{ DocClassifier, SourceClassifier }

object DaffodilPlugin extends AutoPlugin {

  object autoImport {
    val daffodilPackageBinInfos = settingKey[Seq[DaffodilBinInfo]](
      "Information used to create compiled parsers"
    )
    val daffodilPackageBinVersions = settingKey[Seq[String]](
      "Version of daffodil to create saved parsers for. Should only contain a single version--" +
        "specifying multiple versions is deprecated in favor of .daffodilProject(crossDaffodilVersions = ...)"
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
    val daffodilBuildsPlugin = Def.setting {
      // this is a helper to check if a project builds any kind of plugin. This can be accessed
      // like a setting but users cannot change the value. Instead they should change one or
      // more of the daffildBuilds* settings
      daffodilBuildsCharset.value || daffodilBuildsLayer.value || daffodilBuildsUDF.value
    }
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

    /**
     * Allows calling .daffodilProject(...) on an existing SBT Project to convert it to a
     * Daffodil project. This enables the DaffodilPlugin for the project, sets a number of
     * settings, and adds support for daffodil cross versioned subprojects
     */
    implicit class DaffodilProjectOps(val root: Project) extends AnyVal {
      def daffodilProject(crossDaffodilVersions: Seq[String] = Nil): DaffodilProject = {
        DaffodilProject(root, crossDaffodilVersions)
      }
    }

    /**
     * Provides enrichments to ModuleID to provide daffodil specific capabilities
     */
    implicit class DaffodilModuleIDOps(private val moduleId: ModuleID) extends AnyVal {

      /**
       * Specify that a ModuleID is to a saved parser compatible with a specific version of
       * Daffodil. For example:
       *
       *   libraryDependences ++= Seq(
       *     "org.example" % "dfdl-fmt" % "1.0.0" daffodilBin(daffodilVersion.value)
       *   )
       *
       * This adds the dfdl-fmt-1.0.0-daffodilXYZ.bin saved parser as a dependency. Note that
       * SBT will not add this to the classpath like normal dependencies (because it's not a
       * jar), but the path can be extracted from the SBT "update" task. This also adds it to
       * the "provided" scope so that other schema projects can depend on this schema project
       * with a potentially different version of Daffodil and saved parser. This does mean that
       * if schema project A depends on saved parser from Foo, and schema project B depends on
       * project A, then project B must also list Foo as a dependency with daffodilBin(...) if
       * it needs the saved parser.
       */
      def daffodilBin(daffodilVersion: String): ModuleID = {
        moduleId
          .artifacts(
            Artifact(moduleId.name, "parser", "bin", daffodilVersionId(daffodilVersion))
          )
          .withConfigurations(Some("provided"))
      }

      /**
       * Specify that a ModuleID is to a plugin compatible with a specific version of Daffodil.
       * For example:
       *
       *   libraryDependences ++= Seq(
       *     "org.example" % "dfdl-plugin" % "1.0.0" daffodilPlugin(daffodilVersion.value)
       *   )
       *
       * This adds the dfdl-plugin-1.0.0-daffodilXYZ.jar as a dependency and adds it to the
       * classpath. This also adds it to the "provided" scope so that other schema projects can
       * depend on this schema project with a potentially different version of Daffodil and
       * plugin. This does mean that if schema project A depends on plugin Foo, and schema
       * project B depends on project A, then project B must also list Foo as a dependency with
       * daffodilPlugin(...).
       */
      def daffodilPlugin(daffodilVersion: String): ModuleID = {
        moduleId
          .artifacts(Artifact(moduleId.name, daffodilVersionId(daffodilVersion)))
          .withConfigurations(Some("provided"))
      }
    }

  }

  val sbtDaffodilPluginVersion = this.getClass.getPackage.getImplementationVersion()

  /**
   * With the way SemanticVersion works by default it treats SNAPSHOT versions
   * as less than non-SNAPSHOT versions, ie 4.0.0-SNAPSHOT < 4.0.0.  That makes
   * the logic a bit more confusing, so this implicit will cause SNAPSHOT
   * versions to be treated like non-SNAPSHOT as far as determining the correct
   * Scala/Daffodil version to use.
   */
  implicit def normalVersionNumber(s: String): VersionNumber = {
    VersionNumber(s.takeWhile(_ != '-'))
  }

  import autoImport._

  /**
   * Generate string based on a daffodil version that can be used to identify something intended
   * for a use with a specific version of daffodil. This can be used in many places with
   * different limitations, so we remove non alphanumeric characters and make it lowerse.
   */
  def daffodilVersionId(daffodilVersion: String): String = {
    "daffodil" + daffodilVersion.replaceAll("[^a-zA-Z0-9]", "").toLowerCase
  }

  /**
   * generate an artifact classifier name using the optional name and daffodil version
   */
  def classifierName(optName: Option[String], daffodilVersion: String): String = {
    val versionId = daffodilVersionId(daffodilVersion)
    (optName.toSeq ++ Seq(versionId)).mkString("-")
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
    val filteredValues = mappings
      .filterKeys { semSel => SemanticSelector(semSel).matches(version) }
      .values
      .toSeq
    filteredValues
  }

  override lazy val projectSettings: Seq[Setting[_]] = Seq(
    /**
     * Different versions of Daffodil depend on a specific major/minor version of Scala. For
     * example, all versions of Daffodil 3.10.0 and older depend on Scala 2.12.x. Note that
     * Scala ensures binary compatibility, so we can use the latest patch version for any
     * version of Daffodil, as long as we use the correct major/minor version. These Scala
     * versions should be updated anytime a new patch release is made available to ensure we
     * have the latest bug fixes and JDK compatibility. Note that this does mean that we could
     * use a Scala version that a version of Daffodil was not released or tested with, but Scala
     * backwards compatibility guarantees should prevent this from causing issues.
     */
    scalaVersion := {
      // We really only need Scala versions and not full ModuleIds, but using ModuleId.revision
      // makes it easier for scala-steward to detect and automatically update the versions when
      // new Scala patch versions are released.
      val daffodilToScalaVersion = Map(
        ">=4.0.0 " -> ("org.scala-lang" % "scala3-library" % "3.3.7").revision,
        "=3.11.0 " -> ("org.scala-lang" % "scala-library" % "2.13.18").revision,
        "<=3.10.0" -> ("org.scala-lang" % "scala-library" % "2.12.21").revision
      )
      filterVersions(daffodilVersion.value, daffodilToScalaVersion).head
    },

    /**
     * Default Daffodil version
     */
    daffodilVersion := "4.0.0",

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
        val charsetDep = Map(
          ">=4.0.0 " -> "org.apache.daffodil" %% "daffodil-core" % daffodilVersion.value % "provided",
          "<=3.11.0 " -> "org.apache.daffodil" %% "daffodil-io" % daffodilVersion.value % "provided"
        )
        filterVersions(daffodilVersion.value, charsetDep)
      } else {
        Seq()
      }
    },

    /**
     * Add layer compile dependencies if enabled
     */
    libraryDependencies ++= {
      if (daffodilBuildsLayer.value) {
        val layersDep = Map(
          ">=4.0.0 " -> "org.apache.daffodil" %% "daffodil-core" % daffodilVersion.value % "provided",
          "<=3.11.0 " -> "org.apache.daffodil" %% "daffodil-runtime1-layers" % daffodilVersion.value % "provided"
        )
        filterVersions(daffodilVersion.value, layersDep)
      } else {
        Seq()
      }
    },

    /**
     * Add UDF compile dependencies if enabled
     */
    libraryDependencies ++= {
      if (daffodilBuildsUDF.value) {
        val udfDep = Map(
          ">=4.0.0 " -> "org.apache.daffodil" %% "daffodil-core" % daffodilVersion.value % "provided",
          "<=3.11.0 " -> "org.apache.daffodil" %% "daffodil-udf" % daffodilVersion.value % "provided"
        )
        filterVersions(daffodilVersion.value, udfDep)
      } else {
        Seq()
      }
    },

    /**
     * If we are building plugins, we want to make sure they are built with compatability for
     * the minimum version of Java that daffodilVersion supports, regardless of the javac
     * version used for compilation. For example, even when building on Java 17+, we want to
     * make sure plugins that are built for Daffodil 3.10.0 are built with Java 8 compatability.
     * We use daffodilVerison to determine the target JDK version, and the current javac version
     * to figure out the right javacOptions to set.
     */
    javacOptions ++= {
      if (daffodilBuildsPlugin.value) {
        val daffodilPluginJdkCompatMap = Map(
          ">=4.0.0 " -> "17",
          "<=3.11.0" -> "8"
        )
        val jdkCompat = filterVersions(daffodilVersion.value, daffodilPluginJdkCompatMap).head
        val javacOptionsMap = Map(
          ">=9" -> Seq("--release", jdkCompat),
          " =8" -> Seq("-source", jdkCompat, "-target", jdkCompat)
        )
        filterVersions(Properties.javaSpecVersion, javacOptionsMap).head
      } else {
        Seq()
      }
    },

    /**
     * See javacOptions above for details
     */
    scalacOptions ++= {
      if (daffodilBuildsPlugin.value) {
        val daffodilPluginJdkCompatMap = Map(
          ">=4.0.0 " -> "17",
          "<=3.11.0" -> "8"
        )
        val jdkCompat = filterVersions(daffodilVersion.value, daffodilPluginJdkCompatMap).head
        val scalacOptionsMap = Map(
          ">=2.13         " -> Seq("--release", jdkCompat),
          ">=2.12.16 <2.13" -> Seq(s"--target:jvm-$jdkCompat"),
          "<=2.12.15      " -> Seq(s"-target:jvm-1.$jdkCompat")
        )
        filterVersions(scalaVersion.value, scalacOptionsMap).head
      } else {
        Seq()
      }
    },

    /**
     * DFDL schemas are not scala version specific since they just contain resources, so we
     * disable crossPaths so that published jars do not contain a scala version. However, if a
     * project builds any plugins (i.e. charset, layers, UDFs) then we do need to enable
     * crossPaths since those might be implemented in Scala and so is version dependent. If they
     * are implemented purely in Java, projects can override this and change the setting to
     * false if they don't want the scala version in the jar name.
     */
    crossPaths := daffodilBuildsPlugin.value,

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
      val cfg = daffodilVersionId(daffodilVersion)
      Configuration.of(cfg.capitalize, cfg)
    }.toSeq,
    libraryDependencies ++= {
      daffodilPackageBinVersions.value.flatMap { binDaffodilVersion =>
        val cfg = daffodilVersionId(binDaffodilVersion)
        // the Daffodil dependency must ignore the scalaVersion setting and instead use
        // the specific version of scala used for the binDaffodilVersion.
        val daffodilToPackageBinDep = Map(
          ">=4.0.0 " -> Seq(
            "org.apache.daffodil" % "daffodil-core_3" % binDaffodilVersion % cfg,
            "org.apache.daffodil" % "sbt-daffodil-utils_3" % sbtDaffodilPluginVersion % cfg
          ),
          "=3.11.0 " -> Seq(
            "org.apache.daffodil" % "daffodil-japi_2.13" % binDaffodilVersion % cfg,
            "org.apache.daffodil" % "sbt-daffodil-utils_2.13" % sbtDaffodilPluginVersion % cfg
          ),
          "<=3.10.0" -> Seq(
            "org.apache.daffodil" % "daffodil-japi_2.12" % binDaffodilVersion % cfg,
            "org.apache.daffodil" % "sbt-daffodil-utils_2.12" % sbtDaffodilPluginVersion % cfg
          )
        )
        val dafDep = filterVersions(binDaffodilVersion, daffodilToPackageBinDep).head
        // Add logging backends used when packageDaffodilBin outputs log messages
        val daffodilToLogDependency = Map(
          ">=3.5.0" -> "org.slf4j" % "slf4j-simple" % "2.0.9" % cfg,
          "<3.5.0" -> "org.apache.logging.log4j" % "log4j-core" % "2.20.0" % cfg
        )
        val logDep = filterVersions(binDaffodilVersion, daffodilToLogDependency).head
        dafDep :+ logDep
      }.toSeq
    },

    /**
     * define the artifacts, products, and the packageDaffodilBin task that creates the artifacts/products
     */
    packageDaffodilBin / artifacts := {
      val logger = sLog.value
      if (daffodilPackageBinVersions.value.length > 1) {
        logger.warn(
          "Specifying multiple values for daffodilPackageBinVersions is deprecated. Instead use .daffodilProject(crossDaffodilVersions = ...)"
        )
      }
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

      // get all dependencies and resources of this project
      val projectClasspath = (Compile / fullClasspath).value.files

      val mainClass = "org.apache.daffodil.DaffodilSaver"

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
          val ivyCfg = ivyConfigs.find { _.name == daffodilVersionId(daffodilVersion) }.get
          val daffodilJars = Classpaths.managedJars(ivyCfg, classpathTypesVal, updateVal).files

          // Note that order matters here. The projectClasspath might have daffodil jars on it if
          // Daffodil is a compile dependency, which could be a different version from the version
          // of Daffodil we are compiling the schema for. So when we fork Java, daffodilJars must be
          // on the classpath before projectClasspath jars. Also note that daffodilJars
          // includes both the Daffodil jars and the sbt-daffodil-utils jar that contains the
          // DaffodilSaver we are going to fork
          val classpathFiles = daffodilJars ++ projectClasspath

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
            // which API functions to use. This is the mapping for which Daffodil API should be
            // used for a particular "internal API". Note that the DaffodilSaver uses
            // sbt-projectmagic to handle changes to Daffodil package namespaces, so this
            // doesn't need different numbers for those. It's only really needed when we want to
            // use new API functions that aren't available with older verisons of Daffodil.
            val daffodilInternalApiVersionMapping = Map(
              ">=3.9.0" -> "2",
              "<=3.8.0" -> "1"
            )
            val internalApiVersion =
              filterVersions(daffodilVersion, daffodilInternalApiVersionMapping).head

            // If a file exists with the same name as the config option but with the ivy config
            // name as a secondary extension (e.g. config.daffodil390.xml) then use that as the
            // config file. Otherwise use the unmodified config setting. This is useful when
            // different version of Daffodil require different configs.
            val cfgOption = dbi.config
              .map { cfg =>
                val curName = cfg.getName
                val index = curName.lastIndexOf(".")
                val (baseName, ext) = if (index >= 1) curName.splitAt(index) else (curName, "")
                val versionedName = baseName + "." + ivyCfg.name + ext
                val versionedCfg = new File(cfg.getParentFile, versionedName)
                if (versionedCfg.exists) versionedCfg else cfg
              }
              .map { _.toString }
              .getOrElse("")

            val args = jvmArgs ++ Seq(
              "-classpath",
              classpathFiles.mkString(File.pathSeparator),
              mainClass,
              internalApiVersion,
              dbi.schema,
              targetFile.toString,
              dbi.root.getOrElse(""),
              cfgOption
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
    * parsers and adds them as a resource for the TDML files to find and use. Note that we use a
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
    sourceDirectory := {
      if (!daffodilFlatLayout.value) sourceDirectory.value
      else {
        baseDirectory.value / dir
      }
    },
    unmanagedSourceDirectories := {
      if (!daffodilFlatLayout.value) unmanagedSourceDirectories.value
      else {
        nonFlatWarning(Keys.sLog.value, unmanagedSourceDirectories.value)
        Seq(sourceDirectory.value)
      }
    },
    unmanagedResourceDirectories := {
      if (!daffodilFlatLayout.value) unmanagedResourceDirectories.value
      else {
        nonFlatWarning(Keys.sLog.value, unmanagedResourceDirectories.value)
        Seq(sourceDirectory.value)
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

  class DaffodilProject(rootProject: Project, crossProjects: Seq[Project])
    extends CompositeProject {

    /**
     * the way to return multiple projects as a single Project to SBT is via the
     * componentProjects function of CompositeProject
     */
    def componentProjects: Seq[Project] = rootProject +: crossProjects

    /**
     * Provide a way to get a reference to a subproject based on its daffodilVersion. This should
     * not be needed often, but could be used to change a setting for only a subproject, e.g.
     *
     *   val format = (project in file("."))
     *     .settings(...)
     *     .daffodilProject(crossDaffodilVersions = Seq("4.0.0")
     *
     *   format.daffodil("4.0.0") / packageDaffodilBin / publishArtifact := false
     */
    def daffodil(version: String): Project = {
      val crossVersionId = rootProject.id + "_" + daffodilVersionId(version)
      crossProjects
        .find(_.id == crossVersionId)
        .getOrElse(sys.error(s"No crossDaffodilVersion defined for $version"))
    }
  }

  object DaffodilProject {

    /**
     * Converts the root SBT Project into a "daffodil project". Additionally, for each
     * crossVersion defined, an project is created that shares most of the settings with the
     * root project with minor tweaks to support building/testing/publishing/etc. with different
     * versions of Daffodil
     */
    def apply(root: Project, crossVersions: Seq[String]): DaffodilProject = {

      // Settings that we will inject into both the root project and all daffodil cross version
      // projects
      val sharedProjectSettings = Seq(
        // daffodil plugin jars are tied to a specific version of Daffodil due to API and Scala
        // version differences. If this project builds a plugin then we add a classifier that
        // defines the daffodil version. This is similar to how we use classifiers for saved
        // parser artifacts.
        packageBin / artifactClassifier := {
          if (daffodilBuildsPlugin.value)
            Some(daffodilVersionId(daffodilVersion.value))
          else
            None
        },
        packageSrc / artifactClassifier := {
          if (daffodilBuildsPlugin.value)
            Some(daffodilVersionId(daffodilVersion.value) + "-" + SourceClassifier)
          else
            Some(SourceClassifier)
        },
        packageDoc / artifactClassifier := {
          if (daffodilBuildsPlugin.value)
            Some(daffodilVersionId(daffodilVersion.value) + "-" + DocClassifier)
          else
            Some(DocClassifier)
        },

        // The above changes the name so that projects that build daffodil plugins include the
        // cross version id in it. We no longer need crossPaths since the daffodil version
        // provides enough differentiation, and each Daffodil version only supports a single
        // Scala version.
        crossPaths := false,

        // Add src/{main,test}/{scala,java}-daffodil-$daffodilVersion as additional source
        // directories. This directories can be used for Daffodil version specific code and is
        // only used for a single cross version project.
        Compile / unmanagedSourceDirectories ++= (Compile / unmanagedSourceDirectories).value
          .map { d =>
            d.getParentFile / (d.name + "-daffodil-" + daffodilVersion.value)
          },
        Test / unmanagedSourceDirectories ++= (Test / unmanagedSourceDirectories).value.map {
          d =>
            d.getParentFile / (d.name + "-daffodil-" + daffodilVersion.value)
        },

        // set daffodilPackageBinVersions to the version of daffodil this project uses. Saved
        // parsers will only be created if daffodilPackageBinInfos is set
        daffodilPackageBinVersions := Seq(daffodilVersion.value)
      )

      // Settings only used for cross version projects
      val crossProjectSettings = Seq(
        // We use the same baseDirectory as the root project so that all of the sources are
        // shared. However, we need a separate target directory to store managed artifacts--we
        // put that nested inside the target directory of the root.
        baseDirectory := (root / baseDirectory).value,
        target := (root / target).value / daffodilVersionId(daffodilVersion.value),

        // Disable publishing for subprojects. Subproject artificats (e.g. cross versions
        // plugins or saved parsers) have the same name as the root project but with different
        // classifiers (i.e. daffodilXYZ) and are published along with the root project.
        publish := {},
        publishLocal := {},
        publishM2 := {},
        publish / skip := true
      )

      // create a subproject for each of the daffodil cross versions. Each subproject is a clone
      // of the root project with tweaks to some settings. Note that SBT requires that each
      // project have a different base directory. This base directory is only used for build
      // artifacts so we put it inside the target directory of the root project
      val crossProjects = crossVersions.map { version =>
        val crossVersionId = daffodilVersionId(version)
        val crossProjectId = root.id + "_" + crossVersionId
        val crossProjectBase = root.base.getAbsoluteFile / "target" / crossVersionId
        val crossProject = root
          .withId(crossProjectId)
          .in(crossProjectBase)
          .settings(sharedProjectSettings: _*)
          .settings(crossProjectSettings: _*)
          .settings(daffodilVersion := version)
          .enablePlugins(DaffodilPlugin)
        crossProject
      }

      val rootProjectSettings = Seq(
        // publish cross versioned artifacts with the root project artifacts. This is important
        // since all projects have the same name, differing only in classifiers, so we cannot
        // publish subprojects separately since most artifact repositories do not support
        // publishing additional artifacts after a jar has already been published
        crossProjects.flatMap { project =>
          Seq(
            artifacts ++= (project / artifacts).value,
            packagedArtifacts ++= (project / packagedArtifacts).value
          )
        }
      ).flatten

      // Create the root project. We intentionally aggregate() the cross projects, but the set
      // the "aggregate" setting to false--this way users must either explicitly trigger a task
      // in a cross project or change the aggregate setting before running a task if they want
      // aggregation, e.g. sbt "set test/aggregate" test
      val rootProject = root
        .aggregate(crossProjects.map(_.project): _*)
        .settings(aggregate := false)
        .settings(sharedProjectSettings: _*)
        .settings(rootProjectSettings: _*)
        .enablePlugins(DaffodilPlugin)

      new DaffodilProject(rootProject, crossProjects)
    }
  }

}
