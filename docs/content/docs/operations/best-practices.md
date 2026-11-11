---
title: Best Practices
weight: 5
type: docs
---
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

## Avoiding Checkpoint Timeouts

For basic checkpoint settings and storage, follow the
[Flink checkpointing guide](https://nightlies.apache.org/flink/flink-docs-release-2.3/docs/dev/datastream/fault-tolerance/checkpointing/)
for your deployed Flink version.

### Run long calls asynchronously

A synchronous LLM, HTTP, or tool call inside an Action can block checkpoint
progress for the duration of the call. Long computations and other blocking code
inside the Action can also delay checkpoints. For custom external calls, use
`ctx.execute_async(...)` and `await` in an `async def` Python Action, or
`ctx.executeAsync(...).await()` in Java. See [async execution]({{< ref "docs/development/create-agent/actions#async-execution" >}})
for examples.

Checkpointing can continue while these asynchronous calls wait for a response.
For [Java on JDK 11–20]({{< ref "docs/development/create-agent/actions#execution-requirements" >}}),
this requires `parallel-execution.enabled` (enabled by default) and a plan
containing only Java Actions.

The built-in chat, tool-call, context-retrieval, and decision-model Actions enable
async calls by default. Keep their [async settings]({{< ref "docs/operations/configuration#model-calls-tools-and-retries" >}})
enabled.

Configure connection and read timeouts on external clients so that stalled calls
eventually release capacity.

### Bound unfinished inputs

Within each Agent operator subtask, inputs for different keys can be processed
concurrently. Inputs sharing a key are processed one at a time; subsequent inputs
for that key are queued until the current run finishes.

[`max-in-flight-input-records`]({{< ref "docs/operations/configuration#runtime-and-async-execution" >}})
limits the total unfinished inputs per subtask, including both active and queued
inputs. At the limit, admitting another input waits for an existing one to finish,
applying backpressure upstream and potentially delaying checkpoint barriers.

- A larger limit admits more inputs before backpressure starts. If processing
  cannot keep up, it increases buffered data, memory/state usage, and checkpoint
  work without increasing processing capacity. For a hot key, the extra budget
  mainly allows a longer queue.
- A smaller limit bounds that footprint more tightly, but applies backpressure
  sooner and can leave async capacity underused.

Sustained backpressure can delay aligned checkpoints enough to time out. Consider
[unaligned checkpoints](https://nightlies.apache.org/flink/flink-docs-release-2.3/docs/ops/state/checkpointing_under_backpressure/)
when barrier propagation is the bottleneck. They add checkpoint I/O for buffered
network data and cannot preempt blocking Action code.

### Size async thread capacity

[`num-async-threads`]({{< ref "docs/operations/configuration#runtime-and-async-execution" >}})
controls asynchronous execution thread capacity per Agent operator subtask.
Too little capacity can leave calls queued, delay input
completion, and prolong backpressure. The default is twice the detected CPU count.

The thread count can exceed the CPU count. For example, a subtask with one CPU
can use many threads to wait for LLM or HTTP responses concurrently. Increasing
the thread count can improve throughput when calls spend most of their time
waiting for external services. If calls require substantial computation, the
threads compete for the same CPU and can slow down checkpoint processing.
Additional threads and requests also consume memory and connections, and too
many concurrent requests can overload external services or trigger rate limits.

This setting applies to each Agent subtask. Subtasks on the same TaskManager
share its CPU and memory, so increasing the thread count for every subtask
increases their combined resource usage.

Tune it together with
[`max-in-flight-input-records`]({{< ref "docs/operations/configuration#runtime-and-async-execution" >}}),
which controls how many unfinished inputs the operator admits. Raising only the
input limit can add queued work when workers are busy; raising only the thread
count may leave workers idle if too few calls are available. The values need not
match: one input can issue multiple concurrent calls.

### Account for Action State Store latency

With an [Action State Store]({{< ref "docs/operations/consistency#configure-an-action-state-store" >}})
enabled, Action completions and durable call outcomes
are written synchronously. A slow backend can delay Action progress and checkpoint
processing even when the LLM or tool call itself runs asynchronously.

Both Kafka and Fluss also require synchronous backend requests during checkpointing.
Fluss additionally writes the execution records still needed for recovery, so more
retained records mean more writes during this step.

If checkpoints slow down after enabling the store, check backend request latency,
write throughput, availability, and client timeouts alongside
[Flink checkpoint timings](https://nightlies.apache.org/flink/flink-docs-release-2.3/docs/ops/monitoring/checkpoint_monitoring/).
Unaligned checkpoints do not bypass these synchronous operations. See
[Consistency]({{< ref "docs/operations/consistency" >}}) for backend setup and
retention requirements.
