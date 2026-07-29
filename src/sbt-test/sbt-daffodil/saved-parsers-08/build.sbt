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

// This test verifies that packageDaffodilBin/javaOptions defaults to inheriting the -Xss this
// sbt JVM was started with (set via scriptedLaunchOpts in this repo's build.sbt), and that a
// project can still append its own -Xss to take precedence.

val test = (project in file("."))
  .settings(
    version := "0.1",
    name := "test",
    organization := "com.example",
    daffodilPackageBinInfos := Seq(
      DaffodilBinInfo("/test.dfdl.xsd")
    ),
    daffodilVersion := "4.1.0"
  )
  .daffodilProject()
