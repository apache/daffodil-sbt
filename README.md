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

And add the following to `build.sbt`:

```scala
enablePlugins(DaffodilPlugin)
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
with the following parameters:

|Name    |Type             |Reqiured | Description |
|--------|-----------------|---------|-------------|
|schema  |`String`         |yes      |Resource path to the main schema |
|root    |`Option[String]` |no       |Root element in the schema. If `None`, uses the first element in the schema |
|name    |`Option[String]` |no       |If `Some`, includes value in the jar classifier, useful to distinguish saved parsers |
|config  |`Option[File]`   |no       |Path to a configuration file used during compilation |

An example of this settings supporting two roots looks like this:

```scala
daffodilPackageBinInfos := Seq(
  DaffodilBinInfo("/com/example/xsd/mainSchema.dfdl.xsd", Some("record"))
  DaffodilBinInfo("/com/example/xsd/mainSchema.dfdl.xsd", Some("fileOfRecords"), Some("file"))
)
```

You must also define which versions of Daffodil to build comptiable saved
parsers using the `daffodilPackageBinVersions` setting. For example, to build
saved parsers for Daffodil 3.6.0 and 3.5.0:

```scala
daffodilPackageBinVersions := Seq("3.6.0", "3.5.0")
```

Then run `sbt packageDaffodilBin` to generate saved parsers in the `target/`
directory. For example, assuming a schema project with name of "format",
version set to "1.0", and the above configurations, the task would generate the
following saved parsers:

```
target/format-1.0-daffodil350.bin
target/format-1.0-daffodil360.bin
target/format-1.0-file-daffodil350.bin
target/format-1.0-file-daffodil360.bin
```

Note that the artifact names have the suffix "daffodilXYZ".bin, where XYZ is
the version of Daffodil the saved parser is compatible with.

The `publish`, `publishLocal`, `publishM2` and related publish tasks are
modified to automatically build and publish the saved parsers as a new
artifacts.

If used, one may want to use the first value of this setting to configure
`daffodilVersion`, e.g.:

```scala
daffodilVersion := daffodilPackageBinVersions.value.head
```

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

Note that only saved parsers for `daffodilVersion` can be referenced. For this
reason, `daffodilVersion` must also be defined in `daffodilPackageBinVersions`
if `daffodilTdmlUsesPackageBin` is `true`.

This is implemented using a SBT resource generator which some IDE's, like
IntelliJ, do not trigger during builds. So you must either run `sbt Test/compile`
to manually trigger the resource generator, or let SBT handle builds by
enabling the "Use SBT shell for builds" option.

### Layers and User Defined Functions

If your schema project builds a Daffodil layer or user defined function, then
set the `daffodilBuildsLayer` or `daffodilBuildsUDF` setting to true,
respectively. For example:

```scala
daffodilBuildsLayer := true

daffodilBuildsUDF := true
```

Setting either of these values to true adds additional dependencies needed to
build the component.

Note that this also sets the SBT `crossPaths` setting to `true`, which causes
the Scala version to be included in the jar file name, since layer and UDF jars
may be implemented in Scala and are specific to the Scala version used to build
them. However, if your schema project implements layers/UDFs using only Java,
you can override this in build.sbt and remove the Scala version from the jar
name, for example:

```scala
crossPaths := false
```

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

## Testing

This plugin use the [scripted test framework] for testing. Each directory in
`src/sbt-test/sbt-daffodil/` is a small SBT project the uses this plugin, and
defines a `test.script` file that lists sbt commandss and file system checks to
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
