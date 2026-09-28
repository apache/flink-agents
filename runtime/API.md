<!--
Licensed to the Apache Software Foundation (ASF) under one
or more contributor license agreements.  See the NOTICE file
distributed with this work for additional information
regarding copyright ownership.  The ASF licenses this file
to you under the Apache License, Version 2.0 (the
"License"); you may not use this file except in compliance
with the License.  You may obtain a copy of the License at

  http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing,
software distributed under the License is distributed on an
"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
KIND, either express or implied.  See the License for the
specific language governing permissions and limitations
under the License.
-->

# Runtime API contracts

## `CompileUtils.connectToAgent(...)`

The existing Java and Python bridge entry points keep their signatures and serialized plan/event
contracts. No HTTP, cURL, JSON, or IDL contract changes are introduced.

| Runtime backend | Input processing contract | Result |
|---|---|---|
| Flink batch keyed-state backend | One input's complete action chain is drained before the next key | Every keyed input produces its normal output |
| General-purpose keyed-state backend | Action chains continue through mailbox scheduling | Different keys may remain in flight concurrently |

The former graph-construction rejection for explicit `RuntimeExecutionMode.BATCH` with the default
batch state backend is removed. Existing callers do not need to change configuration: explicit
batch mode and bounded jobs that resolve to batch execution are handled according to the backend
that Flink creates at runtime.
