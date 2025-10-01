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

val test = (project in file("."))
  .settings(
    version := "0.1",
    name := "test",
    organization := "com.example",
    // tells SBT to build a jar and put it on the classpath instead of putting src/main/resources on
    // the classpath. This means schema resource paths will resolve to a path inside a jar instead
    // of to a file
    exportJars := true,
    daffodilPackageBinInfos := Seq(
      DaffodilBinInfo("/test.dfdl.xsd"),
      DaffodilBinInfo("/test.dfdl.xsd", Some("test02"), Some("two"))
    ),
    daffodilVersion := "3.6.0",
  )
  .daffodilProject(crossDaffodilVersions = Seq("3.5.0"))
