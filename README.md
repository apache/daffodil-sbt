<!--
  Licensed to the Apache Software Foundation (ASF) under one or more
  contributor license agreements.  See the NOTICE file distributed with
  this work for additional information regarding copyright ownership.
  The ASF licenses this file to You under the Apache License, Version 2.0
  (the "License"); you may not use this file except in compliance with
  the License.  You may obtain a copy of the License at

      http://www.apache.org/licenses/LICENSE-2.0

  Unless required by applicable law or agreed to in writing, software
  distributed under the License is distributed on an "AS IS" BASIS,
  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  See the License for the specific language governing permissions and
  limitations under the License.
-->

# Apache Daffodil SBT Plugin

Plugin to run Daffodil on DFDL schema projects.

## Enable

To enable the plugin, add the following to `project/plugins.sbt`:

```scala
addSbtPlugin("org.apache.daffodil" % "sbt-daffodil" % "<version>")
```

And define a project using the modern SBT project syntax in `build.sbt`,
followed by a call to `daffodilProject()`:

```scala
val format = (project in file("."))
  .settings(...)
  .daffodilProject()
```

## Features

### Common Settings

This plugin configures a number of SBT settings to have better defaults for
DFDL schema projects. This includes setting dependencies for testing (e.g.
daffodil-tdml-processor, junit), juint test options, and more.

By default, this plugin configures the Daffodil dependency to be the latest
version available at the time of the plugins release, but to pin to a specific
Daffodil version set the `daffodilVersion` setting in build.sbt, for example:

```scala
daffodilVersion := "3.6.0"
```

Notably, this plugin sets `scalaVersion`, which should usually not be defined
in schema projects using this plugin. This setting is set to the version of
Scala used for the release of `daffodilVersion` and raised to the [minimum JDK
compatible Scala version] if required.

### Saved Parsers

This plugin adds the ability to create and publish saved parsers of a schema.

For each saved parser to generate, add an entry to the
`daffodilPackageBinInfos` setting. This setting is a `Seq[DaffodilBinInfo]`,
where each element in the sequence defines information to create a saved parser
with parameters describe below.

#### DaffodilBinInfo Parameters

##### schema

Type: `String`

Resource path to the main schema. Since this is a resource path it must start
with a `/` and exclude path elements like `src/main/resources`.

Example:

```scala
schema = "/org/example/xsd/schema.dfdl.xsd"`
```

##### root

Type: `Option[String]`

Root element in the schema used for parsing/unparsing. If `None`, uses the
first element found in the main schema. Defaults to `None` if not provided.

Example:
```scala
root = Some("fileOfRecords")
```

##### name

Type: `Option[String]`

If defined, includes the value in the jar classifier. This is required to
distinguish multiple saved parsers if multiple `DaffodilBinInfo`'s are
specified. Defaults to `None` if not provided.

Example:
```scala
name = Some("file")
```

##### config

Type: `Option[File]`

Path to a configuration file used during compilation, currently only used to
specify tunables. If specified, it should usually use SBT settings to create
the path. Defaults to `None` if not provided.

If a file exists with the same name as the config option, but with a Daffodil
version as the secondary extension (e.g. `config.daffodil390.xml`), then that
file will be used instead of the original config file, but only when creating
saved parsers for that specific version of Daffodil. This can be useful when
multiple versions are specified in `daffodilPackageBinVersions` but those
versions have configuration incompatibilities.

Example:

If the configuration file is in `src/main/resources/`, then use:

```scala
config = Some(resourceDirectory.value / "path" / "to" / "config.xml")
```

If the configuration file is in the root of the SBT project, then use:

```scala
config = Some(baseDirectory.value / "path" / "to" / "config.xml")
```

#### Saved Parser Examples

An example of this settings supporting two roots looks like this:

```scala
daffodilPackageBinInfos := Seq(
  DaffodilBinInfo("/com/example/xsd/mainSchema.dfdl.xsd", Some("record"))
  DaffodilBinInfo("/com/example/xsd/mainSchema.dfdl.xsd", Some("fileOfRecords"), Some("file"))
)
```

Then run `sbt packageDaffodilBin` to generate saved parsers in the `target/`
directory. For example, assuming a schema project with `name := "format"`,
`version := "1.0"`, and `daffodilVersion := "3.5.0"`, and the above
configuration, the task would generate the following saved parsers:

```
target/format-1.0-daffodil350.bin
target/format-1.0-file-daffodil350.bin
```

The saved parser artifact names have the suffix `daffodilXYZ.bin`, where `XYZ`
is the version of Daffodil the saved parser is compatible with.

The `publish`, `publishLocal`, `publishM2` and related publish tasks are
modified to automatically build and publish the saved parsers as new artifacts.
To disable publishing saved parsrs, add the following setting to build.sbt:

```scala
packageDaffodilBin / publishArtifact := false
```

Some complex schemas require more memory or a larger stack size than Java
provides by default. Change the `packageDaffodilBin / javaOptions` setting in
build.sbt to increase these sizes, or more generally to specify options to
provide to the JVM used to save parsers. For example:

```scala
packageDaffodilBin / javaOptions ++= Seq("-Xmx8G", "-Xss4m")
```

In some cases it may be useful to enable more verbose logging when building a
saved parser. To configure the log level, add the following to build.sbt:

```scala
packageDaffodilBin / logLevel := Level.Info
```

The above setting defaults to `Level.Warn` but can be set to `Level.Info` or
`Level.Debug` for more verbosity. Note that this does not affect Schema
Definition Warning or Error diagnostics--it only affects the Daffodil logging
mechanism, usually intended for Daffodil developers.

It can sometimes be useful to build a saved parser using the CLI or in an IDE.
Run the following command to output a CLI command that is equivalent to the
`packageDaffodilBin` task:

```bash
sbt "export packageDaffodilBin"
```

The resulting command can then be run manually in a CLI, copy/pasted into a
script, configured in an IDE/debugger, etc. Note that the command contains
absolute paths and syntax specific to the current system--it is unlikely to
work on systems other than the one where it is generated.

### Saved Parsers in TDML Tests

For schemas that take a long time to compile, it is often convenient to use a
saved parser created by the `packageDaffodilBin` task in TDML tests. If this
is the case, set the following:

```scala
daffodilTdmlUsesPackageBin := true
```

When set to `true` , running `sbt test` automatically triggers
`packageDaffodilBin` and puts the resulting saved parsers on the classpath so
that TDML files can reference them. Note that when referencing saved parsers
from a TDML file, version numbers and `daffodilXYZ` are excluded--this way TDML
files do not need to be updated when a version is changed. For example, a TDML
file using a saved parser created at `target/format-1.0-file-daffodil350.bin`
is referenced like this:

```xml
<parserTestCase ... model="/format-file.bin">
```

This is implemented using a SBT resource generator which some IDEs, like
IntelliJ, do not trigger during builds. So you must either run `sbt Test/compile`
to manually trigger the resource generator, or let SBT handle builds by
enabling the "Use SBT shell for builds" option.

### Daffodil Plugins

#### Projects that Build Plugins

If your schema project builds a Daffodil charset, layer, or user defined
function plugin, then set the `daffodilBuildsCharset`, `daffodilBuildsLayer`,
or `daffodilBuildsUDF` setting to true, respectively. For example:

```scala
daffodilBuildsCharset := true

daffodilBuildsLayer := true

daffodilBuildsUDF := true
```

Setting any of these values to true adds additional dependencies needed to
build the component.

The classifer of plugin jars is set to `daffodilXYZ`, where `XYZ` indicates the
version of Daffodil it was compiled with. This is important since plugins might
not be compatible with all Daffodil versions due to Scala or Daffodil API
differences.

See [Cross-Building](#cross-building) to build plugins in support of multiple
versions of Daffodil.

#### Projects that Use Plugins

Projects that use Daffodil charset, layer, or user defined function plugin
should specify the dependency using the `daffodilPluginDependencies` setting.
Note that the `daffodilXYZ` classifier should not be included and `%%` should
not be used even if the plugin is written in Scala. For example:

```scala
daffodilPluginDependencies := Seq(
  "org.example" % "daffodil-layer-plugin" % "1.0.0"
)
```

The appropriate `daffodilXYZ` classifier will be added to the dependency based
on the `daffodilVersion` of the project. The plugin dependency will also be
added in the `"provided"` scope--this avoids potential issues with transitive
dependencies, but does mean that if a schema project depends on a plugin, even
transitively, it must specify it in `daffodilPluginDependencies`.

### Flat Directory Layout

Instead of using the standard `src/{main,test}/{scala,resources}/` directory
layout that SBT defaults to, a flatter layout can be used for simple schemas,
like when testing or creating examples. One can set `daffodilFlatLayout` to
enable this:

```scala
daffodilFlatLayout := true
```

This configures SBT to expect all compile source and resource files to be in a
root `src/` directory, and all test source and resource files to be in a root
`test/` directory. Source files are those that end with `*.scala` or `*.java`,
and resource files are anything else.

### Cross-Building

In some cases it is helpful to have a single SBT project that supports the
ability to build/test/generate saved parsers/etc. for multiple versions of
Daffodil. To do so, define a project as normal and call `.daffodilProject()`
passing in a `Seq` of additional daffodil versions to support. For example:

```scala
val format = project in file(".")
  .settings(
    ...
    daffodilVersion := "3.10.0"
  )
  .daffodilProject(crossDaffodilVersions = Seq("3.11.0", "4.0.0"))
```

Note that the `daffodilVersion` in the root project should not be listed in the
`crossDaffodilVersions` sequence.

For each Daffodil version defined in `crossDaffodilVersions`, a subproject is
created that is a copy of the root project but with `_daffodilXYZ` appended to
the project ID. For example, the example above creates three projects:
`format`, `format_daffodil3110`, `and format_daffodil400`. Each of these
projects has the DaffodilPlugin enabled and is configured to
build/test/publish/etc. with their respective daffodil versions. Additional
changes made to the projects described below.

#### Source Files

The root project and subprojects share the same `src/` and `lib/` directories.
Additionally, source files specific to a particular version of Daffodil can be
placed in `src/{main,test}/{scala,java}-daffodil-<daffodilVersion>`. This is
useful in cases where plugins or tests require different code for different
Daffodil versions, usually due to Scala or Daffodil API changes.

#### Build Artifacts

Build artifacts (e.g. compiled plugin classes, saved parsers) are stored in the
`target/daffodilXYZ` directory. For example, saved parsrs might be these
locations:

```
target/format-1.0-daffodil3100.bin
target/daffodil3110/format-1.0-file-daffodil3110.bin
target/daffodil400/format-1.0-file-daffodil400.bin
```

#### Publishing

Publishing from subprojects is disabled. Instead, all subproject artifacts
(e.g. compiled plugins, saved parsers, javadocs, sources) are published as
artifacts to the root project, using classifiers for differentiation.

#### Aggregation

The root project does aggregate the subprojects, but sets the `aggregate`
setting to `false`. This means that sbt tasks are only evaluated on the root
project by default.

To run tasks on a subproject you can explicitly specify the project:

```bash
# run tests for only Daffodil 4.0.0
sbt format_daffodil400/test
```

Or change the `aggregate` setting to `true` before running the task. Note that
aggregated tasks run in parallel. With many cross versions, this can require
very large amounts of memory--it may be useful to disable parallel execution
for the more resource intensive tasks like packageDaffodilBin or tests:

```bash
# run tests for root and all subprojects, disabling parallel execution of tests
# and creating saved parsers
sbt "set aggregate:= true" \
    "set packageDaffodilBin / parallelExecution := false" \
    "set test / parallelExecution := false" \
    "test"
```

#### Different Subproject Settings

In rare cases it could be necessary to change a subproject setting to something
different than that of the root project. This can be done by getting a
reference to the subproject by calling `.daffodil(<version>)` on the root
project and modifying the settings as usual. For example, to disable publishing
of only the "4.0.0" saved parser:

```scala
format.daffodil("4.0.0") / packageDaffodilBin / publishArtifact := false
```

## Plugin Testing

This plugin use the [scripted test framework] for testing. Each directory in
`src/sbt-test/sbt-daffodil/` is a small SBT project the uses this plugin, and
defines a `test.script` file that lists sbt commands and file system checks to
run.

To run all tests, run either of these commands:

```bash
sbt test
sbt scripted
```

To run a single scripted test, run the command:

```bash
sbt "scripted sbt-daffodil/<test_name>"
```

Where `<test_name>` is the name of a directory in `src/sbt-test/sbt-daffodil/`.

When a scripted test fails it is sometimes helpful to directly run SBT commands
within the test directory. To do so, publish the plugin locally, cd to the
scripted test directory, and run SBT defining the version of the plugin that
was just published, for example:

```bash
sbt publishLocal
cd src/sbt-test/sbt-daffodil/<test_name>
sbt -Dplugin.version=1.0.0-SNAPSHOT
```

## License

Apache Daffodil SBT Plugin is licensed under the [Apache License, v2.0].

[Apache License, v2.0]: https://www.apache.org/licenses/LICENSE-2.0
[scripted test framework]: https://www.scala-sbt.org/1.x/docs/Testing-sbt-plugins.html
[minimum JDK compatible Scala version]: https://docs.scala-lang.org/overviews/jdk-compatibility/overview.html
