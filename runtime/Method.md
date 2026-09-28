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

# Runtime method contracts

## `ActionExecutionOperator.waitForCurrentInputInBatchMode()`

This internal method preserves Flink's batch keyed-state contract while keeping the streaming
execution model unchanged.

- Input: none. The method reads the backend classification captured during `open()`.
- Output: none.
- Batch keyed-state backend: yields to the task mailbox until the operator has no processing key.
  The current input's synchronous and asynchronous action continuations therefore finish before
  the input loop can advance to another key.
- Other keyed-state backends: returns immediately, preserving cross-key mailbox concurrency.
- Failure: exceptions raised while mailbox continuations run propagate to `processElement()` and
  fail the task. They are not retried or absorbed by this method.

The backend check uses `BatchExecutionKeyedStateBackend`, whose fully qualified class name is stable
across the supported Flink 1.20 and 2.x lines even though the class moved between Flink artifacts.
