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

package org.apache.daffodil;

import java.lang.reflect.Method;
import java.nio.channels.FileChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.net.URI;
import java.net.URL;
import java.util.List;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.DocumentBuilder;

import org.w3c.dom.Document;
import org.w3c.dom.NodeList;
import org.w3c.dom.Node;

// We need a special customized classpath, and the easiest way to do that within SBT is by
// forking. But the only thing that can save a parser via forking is the Daffodil CLI, which we
// do not publish. So instead we create this class that has a static main that we can fork to
// compile and save schemas using the version of Daffodil that is on the classpath. Note that it
// also uses reflection so that it is not tied to any specific version of Daffodil. This is
// fragile, but the Daffodil Jave API is pretty set in stone at this point, so this reflection
// shouldn't break. We also write this in Java so it is not tied to a specific Scala version,
// since different versions of Daffodil use different versions of Scala.
public class DaffodilSaver {

  /**
   * Usage: daffodilSaver <apiVersion> <schemaResource> <outputFile> <root> <config>
   *
   * If <root> or <config> is unknown/not-provided, they must be the empty string
   */
  public static void main(String[] args) {
    try {
      run(args);
    } catch (Exception e) {
      e.printStackTrace();
      System.exit(1);
    }
  }

  private static void run(String[] args) throws Exception {
    assert args.length == 5 : "DaffodilPlugin did not provide the correct number of arguments when forking DaffodilSaver";

    // the "version" of the Daffodil API to use. Note that this is not the same as the Daffodil
    // version, but is related. See the "daffodilInternalAPIVersionMapping" in the plugin code
    // for an explanation of why we have this and what version of Daffodil it represents.
    int apiVersion = Integer.parseInt(args[0]);

    String schemaResource = args[1];
    URL schemaUrl = DaffodilSaver.class.getResource(schemaResource);
    if (schemaUrl == null) {
      System.err.println("failed to find schema resource: " + schemaResource);
      System.exit(1);
    }

    WritableByteChannel output = FileChannel.open(
      Paths.get(args[2]),
      StandardOpenOption.CREATE,
      StandardOpenOption.WRITE);
    String root = !args[3].isEmpty() ? args[3] : null;
    String config = !args[4].isEmpty() ? args[4] : null;

    // parameter types
    Class<?> cURI = URI.class;
    Class<?> cString = String.class;
    Class<?> cWritableByteChannel = WritableByteChannel.class;

    // get the Compiler, ProcessorFactory, and DataProcessor classes and the functions we need
    // to invoke on those classes. Note that we use JAPI because its easier to use via
    // reflection than the Scala API and it is much smaller and easier to use than the lib API
    Class<?> daffodilClass = Class.forName("org.apache.daffodil.japi.Daffodil");
    Method daffodilCompiler = daffodilClass.getMethod("compiler");

    Class<?> compilerClass = Class.forName("org.apache.daffodil.japi.Compiler");
    Method compilerWithTunable = compilerClass.getMethod("withTunable", cString, cString);
    // the compileResource method added in Daffodil 3.9.0 allows for depersonalized diagnostics
    // and better reproducibility of saved parsers--use it instead of compileSource for newer
    // versions of Daffodil
    Method compilerCompile = null;
    switch (apiVersion) {
      case 1:
        compilerCompile = compilerClass.getMethod("compileSource", cURI, cString, cString);
        break;
      case 2:
        compilerCompile = compilerClass.getMethod("compileResource", cString, cString, cString);
        break;
    }

    Class<?> processorFactoryClass = Class.forName("org.apache.daffodil.japi.ProcessorFactory");
    Method processorFactoryIsError = processorFactoryClass.getMethod("isError");
    Method processorFactoryOnPath = processorFactoryClass.getMethod("onPath", cString);
    Method processorFactoryGetDiagnostics = processorFactoryClass.getMethod("getDiagnostics");

    Class<?> dataProcessorClass = Class.forName("org.apache.daffodil.japi.DataProcessor");
    Method dataProcessorIsError = dataProcessorClass.getMethod("isError");
    Method dataProcessorSave = dataProcessorClass.getMethod("save", cWritableByteChannel);
    Method dataProcessorGetDiagnostics = processorFactoryClass.getMethod("getDiagnostics");

    // val compiler = Daffodil.compiler()
    Object compiler = daffodilCompiler.invoke(null);

    // compiler = compiler.withTunable(...)
    if (config != null) {
      DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
      DocumentBuilder builder = factory.newDocumentBuilder();
      Document document = builder.parse(config);
      NodeList tunables = document.getElementsByTagName("tunables");
      for (int i = 0; i < tunables.getLength(); i++) {
        Node tunablesNode = tunables.item(i);
        NodeList children = tunablesNode.getChildNodes();
        for (int j = 0; j < children.getLength(); j++) {
          Node node = children.item(j);
          compiler = compilerWithTunable.invoke(compiler, node.getNodeName(), node.getTextContent());
        }
      }
    }

    // val processorFactory = compiler.compileSource(schemaUrl.toURI, root, None)  // < 3.9.0
    // val processorFactory = compiler.compileResource(name, root, None)       // >= 3.9.0
    Object schemaArg = null;
    switch (apiVersion) {
      case 1:
        schemaArg = schemaUrl.toURI();
        break;
      case 2:
        schemaArg = schemaResource;
        break;
    }
    Object processorFactory = compilerCompile.invoke(compiler, schemaArg, root, null);

    // val processorFactoryDiags = processorFactory.getDiagnostics()
    List<?> processorFactoryDiags = (List<?>) processorFactoryGetDiagnostics.invoke(processorFactory);
    printDiagnostics(processorFactoryDiags);

    // if (processorFactory.isError) System.exit(1)
    if ((boolean) processorFactoryIsError.invoke(processorFactory)) System.exit(1);

    // val dataProcessor = processorFactory.onPath("/")
    Object dataProcessor = processorFactoryOnPath.invoke(processorFactory, "/");

    // val dataProcessorDiags = dataProcessor.getDiagnostics()
    List<?> dataProcessorDiags = (List<?>) dataProcessorGetDiagnostics.invoke(dataProcessor);
    printDiagnostics(dataProcessorDiags);

    // if (dataProcessor.isError) System.exit(1)
    if ((boolean) dataProcessorIsError.invoke(dataProcessor)) System.exit(1);

    // dataProcessor.save(output)
    dataProcessorSave.invoke(dataProcessor, output);

    System.exit(0);
  }


  static private void printDiagnostics(List<?> diags) throws Exception {
    Class<?> diagnosticClass = Class.forName("org.apache.daffodil.japi.Diagnostic");
    Method diagnosticIsError = diagnosticClass.getMethod("isError");
    Method diagnosticToString = diagnosticClass.getMethod("toString");

    for (Object d : diags) {
      String msg = (String) diagnosticToString.invoke(d);
      boolean isError = (boolean) diagnosticIsError.invoke(d);
      String level = isError ? "error" : "warning";
      System.err.println("[" + level + "] " + msg);
    }
  }

}
