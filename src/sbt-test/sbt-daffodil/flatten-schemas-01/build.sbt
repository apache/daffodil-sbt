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

val checkFlattened = taskKey[Unit](
  "Check the file names and updated references of the flattened schemas"
)

/**
 * The name of a flattened schema whose original file name was too long. It is truncated so that
 * the serial number, the file name, and the extension total exactly 64 characters.
 */
val longName = "02_this_is_a_very_long_dfdl_schema_file_name_that_is_lo.dfdl.xsd"

/**
 * The flattened name of a schema that comes from a dependency JAR (daffodil-lib, which comes from
 * the Daffodil dependency). Files in a JAR are grouped with the other files in the same directory
 * of the same JAR, and that group sorts alphabetically between the com/ and org/ directories of
 * this project.
 */
val generalFormat = "03_DFDLGeneralFormat.dfdl.xsd"

val test = (project in file("."))
  .settings(
    version := "0.1",
    name := "test",
    organization := "com.example",
    daffodilVersion := "3.6.0",
    checkFlattened := {
      val log = streams.value.log
      val flatDir = target.value / "flatDir"
      val names = IO.listFiles(flatDir).map(_.getName).sorted

      def expect(cond: Boolean, msg: => String): Unit =
        if (cond) log.info(s"ok: $msg")
        else throw new MessageOnlyException(s"$msg, files found: ${names.mkString(", ")}")

      def text(name: String): String = IO.read(new java.io.File(flatDir, name))

      def ref(name: String): String = s"""schemaLocation="$name""""

      // Each file is named "<serial>_<file name>" where the serial number comes from the directory
      // a file was found in. All of the directories with files being flattened are sorted
      // alphabetically and numbered starting at 01, so this project has:
      //   01: the root of the resource directory (main.dfdl.xsd)
      //   02: com/example/format
      //   03: org/apache/daffodil/xsd inside a dependency JAR
      //   04: org/apache/test
      expect(
        names.contains("01_main.dfdl.xsd"),
        "file in the root resource directory is named 01_main.dfdl.xsd"
      )
      expect(
        names.contains("02_baseFormat.dfdl.xsd"),
        "file in com/example/format is named 02_baseFormat.dfdl.xsd"
      )
      expect(
        names.contains("04_other.dfdl.xsd"),
        "file in org/apache/test is named 04_other.dfdl.xsd"
      )
      expect(names.contains(generalFormat), s"file from a dependency JAR is named $generalFormat")

      // files from the same directory, even if that directory is inside of a JAR, share a serial
      // number and each directory has exactly one serial number
      expect(
        names.count(_.startsWith("02_")) == 2,
        "both files in com/example/format start with the 02 serial number"
      )
      expect(
        names.exists(_.startsWith("03_")),
        "files from the JAR directory share the 03 serial number"
      )
      expect(
        names.forall(n => Seq("01", "02", "03", "04").contains(n.split("_")(0))),
        "every file starts with one of the four serial numbers"
      )

      // the whole file name, including the serial number and extension, is 64 characters or less
      expect(names.forall(_.length <= 64), "no flattened file name is more than 64 characters")
      expect(
        names.contains(longName) && longName.length == 64,
        s"file name that was too long is truncated to $longName"
      )

      // references are updated to the new file names, without any directories
      expect(
        text("01_main.dfdl.xsd").contains(ref("02_baseFormat.dfdl.xsd")) &&
          text("01_main.dfdl.xsd").contains(ref(generalFormat)) &&
          text("01_main.dfdl.xsd").contains(ref("04_other.dfdl.xsd")),
        "references in the root schema use the new file names"
      )
      expect(
        text("02_baseFormat.dfdl.xsd").contains(ref(longName)) &&
          text("02_baseFormat.dfdl.xsd").contains(ref("04_other.dfdl.xsd")),
        "references in a referenced schema use the new file names"
      )
      expect(
        text("04_other.dfdl.xsd").contains(ref("02_baseFormat.dfdl.xsd")),
        "references relative to the resource root use the new file names"
      )
      expect(
        !names.exists(_.contains("__")) && !text("01_main.dfdl.xsd").contains("__"),
        "flattened files do not use the old directory based names"
      )
    }
  )
  .daffodilProject()
