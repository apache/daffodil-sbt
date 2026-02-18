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

// Not an actual layer. This is only used to show plugins can include Scala version specific
// files. This code should only compile on Scala 2.13 (used by Daffodil 3.11.0)

object OnlyScala213 {
  val l: LazyList[Int] = LazyList(1, 2, 3) // fails in Scala 2.12
  val s = 'mySymbol // fails in Scala 3
}
