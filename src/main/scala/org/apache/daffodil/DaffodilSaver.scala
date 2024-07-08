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
import java.nio.channels.FileChannel
import java.nio.channels.WritableByteChannel
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import scala.collection.JavaConverters._

// We need a special customized classpath, and the easiest way to do that within SBT is by
// forking. But the only thing that can save a parser via forking is the Daffodil CLI, which we
// do not publish. So instead we create this class that has a static main that we can fork to
// compile and save schemas using the version of Daffodil that is on the classpath. Note that it
// also uses reflection so that it is not tied to any specific version of Daffodil. This is
// fragile, but the Jave API is pretty set in stone at this point, so this reflection shouldn't
// break.
object DaffodilSaver {

  /**
   * Usage: daffodilReflectionSave <schemaFile> <outputFile> <root> <config>
   *
   * If <root> or <config> is unknown/not-provided, they must be the empty string
   */
  def main(args: Array[String]): Unit = {

    if (args.length != 4) System.err.println(s"[error] four arguments are required")

    val schemaFile = new File(this.getClass.getResource(args(0)).toURI)
    val output = FileChannel.open(
      Paths.get(args(1)),
      StandardOpenOption.CREATE,
      StandardOpenOption.WRITE,
    )
    val root = if (args(2) != "") args(2) else null
    val config = if (args(3) != "") args(3) else null

    // parameter types
    val cFile = classOf[File]
    val cString = classOf[String]
    val cWritableByteChannel = classOf[WritableByteChannel]

    // get the Compiler, ProcessorFactory, and DataProcessor classes and the functions we need
    // to invoke on those classes. Note that we use JAPI because its easier to use via
    // reflection than the Scala API and it is much smaller and easier to use then the lib API
    val daffodilClass = Class.forName("org.apache.daffodil.japi.Daffodil")
    val daffodilCompiler = daffodilClass.getMethod("compiler")

    val compilerClass = Class.forName("org.apache.daffodil.japi.Compiler")
    val compilerWithTunable = compilerClass.getMethod("withTunable", cString, cString)
    val compilerCompileFile = compilerClass.getMethod("compileFile", cFile, cString, cString)

    val processorFactoryClass = Class.forName("org.apache.daffodil.japi.ProcessorFactory")
    val processorFactoryIsError = processorFactoryClass.getMethod("isError")
    val processorFactoryOnPath = processorFactoryClass.getMethod("onPath", cString)
    val processorFactoryGetDiagnostics = processorFactoryClass.getMethod("getDiagnostics")

    val dataProcessorClass = Class.forName("org.apache.daffodil.japi.DataProcessor")
    val dataProcessorIsError = dataProcessorClass.getMethod("isError")
    val dataProcessorSave = dataProcessorClass.getMethod("save", cWritableByteChannel)
    val dataProcessorGetDiagnostics = processorFactoryClass.getMethod("getDiagnostics")

    val diagnosticClass = Class.forName("org.apache.daffodil.japi.Diagnostic")
    val diagnosticIsError = diagnosticClass.getMethod("isError")
    val diagnosticToString = diagnosticClass.getMethod("toString")

    def printDiagnostics(diags: java.util.List[Object]): Unit = {
      diags.asScala.foreach { d =>
        // val msg = d.toString
        val msg = diagnosticToString.invoke(d).asInstanceOf[String]
        // val isError = d.isError
        val isError = diagnosticIsError.invoke(d).asInstanceOf[Boolean]
        val level = if (isError) "error" else "warning"
        System.err.println(s"[$level] $msg")
      }
    }

    // val compiler = Daffodil.compiler()
    var compiler = daffodilCompiler.invoke(null)

    // compiler = compiler.withTunable(...)
    if (config != null) {
      val configXml = scala.xml.Utility.trim(scala.xml.XML.loadFile(config))
      (configXml \ "tunables").foreach { tunablesNode =>
        tunablesNode.child.foreach { node =>
          compiler = compilerWithTunable.invoke(compiler, node.label, node.text)
        }
      }
    }

    // val processorFactory = compiler.compileFile(schemaFile, root, None)
    val processorFactory = compilerCompileFile
      .invoke(compiler, schemaFile, root, null)

    // val processorFactoryDiags = processorFactory.getDiagnostics()
    val processorFactoryDiags = processorFactoryGetDiagnostics
      .invoke(processorFactory)
      .asInstanceOf[java.util.List[Object]]
    printDiagnostics(processorFactoryDiags)

    // if (processorFactory.isError) System.exit(1)
    if (processorFactoryIsError.invoke(processorFactory).asInstanceOf[Boolean]) System.exit(1)

    // val dataProcessor= processorFactory.onPath("/")
    val dataProcessor = processorFactoryOnPath.invoke(processorFactory, "/")

    // val dataProcessorDiags = dataProcessor.getDiagnostics()
    val dataProcessorDiags = dataProcessorGetDiagnostics
      .invoke(dataProcessor)
      .asInstanceOf[java.util.List[Object]]
    printDiagnostics(dataProcessorDiags)

    // if (dataProcessor.isError) System.exit(1)
    if (dataProcessorIsError.invoke(dataProcessor).asInstanceOf[Boolean]) System.exit(1)

    // dataProcessor.save(output)
    dataProcessorSave.invoke(dataProcessor, output)

    System.exit(0)
  }

}
