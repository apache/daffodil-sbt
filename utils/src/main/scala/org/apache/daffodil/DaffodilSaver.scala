/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License") you may not use this file except in compliance with
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

import java.nio.channels.FileChannel
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import scala.jdk.CollectionConverters._

// When creating saved parsers using packageDaffodilBin, we need to create a saved parser for a
// different version of Daffodil than is added to libraryDependencies. For this reason, we have
// this DaffodilSaver class, which can be forked with a Different versionof Daffodil on the
// classpath. Note that we use sbt-projectmatrix to compile this class into three separate jars
// for each version of Scala/Daffodil (Scala 2.12 is used for Daffodil 3.10.0 and older, Scala
// 2.13 is used for Daffodil 3.11.0, and Scala 3.x is used for Daffodil 4.0.0 and newer. WWe
// expect the sbt plugin to set up the classpath to include the correct jar for the version of
// Daffodil being used when forking this class.
object DaffodilSaver {

  /**
   * Usage: daffodilSaver <apiVersion> <schemaResource> <outputFile> <root> <config>
   *
   * If <root> or <config> is unknown/not-provided, they must be the empty string
   */
  def main(args: Array[String]): Unit = {

    if (args.length != 5) {
      throw new Exception(
        "DaffodilPlugin did not provide the correct number of arguments when forking DaffodilSaver"
      )
    }

    // the "version" of the Daffodil API to use. Note that this is not the same as the Daffodil
    // version, but is related. See the "daffodilInternalAPIVersionMapping" in the plugin code
    // for an explanation of why we have this and what version of Daffodil it represents.
    val apiVersion = Integer.parseInt(args(0))

    val schemaResource = args(1)
    val schemaUrl = this.getClass.getResource(schemaResource)
    if (schemaUrl == null) {
      System.err.println("failed to find schema resource: " + schemaResource)
      System.exit(1)
    }

    val output =
      FileChannel.open(Paths.get(args(2)), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
    val root = if (!args(3).isEmpty()) args(3) else null
    val config = if (!args(4).isEmpty()) args(4) else null

    val tunables =
      if (config != null) {
        val configXml = scala.xml.Utility.trim(scala.xml.XML.loadFile(config))
        (configXml \ "tunables").flatMap { tunablesNode =>
          tunablesNode.child.map { node => (node.label, node.text) }
        }.toMap
      } else {
        Map.empty[String, String]
      }
    val jTunables = new java.util.HashMap[String, String](tunables.asJava)

    val compiler = DaffodilAPI
      .compiler()
      .withTunables(jTunables)

    val processorFactory = apiVersion match {
      case 1 => compiler.compileSource(schemaUrl.toURI, root, null)
      case 2 => compiler.compileResource(schemaResource, root, null)
    }
    if (processorFactory.isError()) {
      printDiagnostics(processorFactory.getDiagnostics.asScala.toSeq)
      System.exit(1)
    }

    val dataProcessor = processorFactory.onPath("/")
    if (dataProcessor.isError()) {
      printDiagnostics(dataProcessor.getDiagnostics.asScala.toSeq)
      System.exit(1)
    }

    // print warning diagnostics if they exist
    printDiagnostics(dataProcessor.getDiagnostics.asScala.toSeq)

    dataProcessor.save(output)
  }

  private def printDiagnostics(diags: Seq[DaffodilAPI.Diagnostic]): Unit = {
    diags.foreach { d =>
      val msg = d.toString()
      val level = if (d.isError) "error" else "warning"
      System.err.println(s"[$level] $msg")
    }
  }
}
