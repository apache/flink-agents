---
title: Monitoring
weight: 3
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

## Metric

### Built-in Metrics

We offer data monitoring for built-in metrics, which includes events and actions. 

| Scope       | Metrics                                          | Description                                                                      | Type  |
|-------------|--------------------------------------------------|----------------------------------------------------------------------------------|-------|
| **Agent**   | numOfEventProcessed                              | The total number of Events this operator has processed.                          | Count |
| **Agent** | numOfEventProcessedPerSec                        | The number of Events this operator has processed per second.                     | Meter |
| **Agent** | numOfActionsExecuted                             | The total number of actions this operator has executed.                          | Count |
| **Agent** | numOfActionsExecutedPerSec                       | The number of actions this operator has executed per second.                     | Meter |
| **Action**  | <action_name>.numOfActionsExecuted | The total number of actions this operator has executed for a specific action name. | Count |
| **Action**  | <action_name>.numOfActionsExecutedPerSec | The number of actions this operator has executed per second for a specific action name. | Meter |

#### 

### How to add custom metrics

In Flink Agents, users implement their logic by defining custom Actions that respond to various Events throughout the Agent lifecycle. To support user-defined metrics, we introduce two new properties: `agent_metric_group` and `action_metric_group` in the RunnerContext. These properties allow users to create or update global metrics and independent metrics for actions. For an introduction to metric types, please refer to the [Metric types documentation](https://nightlies.apache.org/flink/flink-docs-release-1.20/docs/ops/metrics/#metric-types).

Here is the user case example:

{{< tabs "Custom Metrics" >}}

{{< tab "Python" >}}
```python
class MyAgent(Agent):
    @action(InputEvent)
    @staticmethod
    def first_action(event: Event, ctx: RunnerContext):  # noqa D102
        start_time = time.time_ns()

        # the action logic
        ...

        # Access the main agent metric group
        metrics = ctx.agent_metric_group

        # Update global metrics
        metrics.get_counter("numInputEvent").inc()
        metrics.get_meter("numInputEventPerSec").mark()

        # Access the per-action metric group
        action_metrics = ctx.action_metric_group
        action_metrics.get_histogram("actionLatencyMs") \
            .update(int(time.time_ns() - start_time) // 1000000)
```
{{< /tab >}}

{{< tab "Java" >}}
```java
public class MyAgent extends Agent {

    @Action(listenEvents = {InputEvent.class})
    public static void firstAction(InputEvent event, RunnerContext ctx) throws Exception {
        long startTime = System.currentTimeMillis();
        
        // the action logic
        ...
        
        FlinkAgentsMetricGroup metrics = ctx.getAgentMetricGroup();

        metrics.getCounter("numInputEvent").inc();
        metrics.getMeter("numInputEventPerSec").markEvent();

        FlinkAgentsMetricGroup actionMetrics = ctx.getActionMetricGroup();
        actionMetrics
                .getHistogram("actionLatencyMs")
                .update(System.currentTimeMillis() - startTime);
    }
}
```
{{< /tab >}}

{{< /tabs >}}


### How to check the metrics with Flink executor

Flink agents enable the reporting of metrics to external systems by creating a metric identifier prefix in the format `<host>.taskmanager.<tm_id>.<job_name>.<operator_name>.<subtask_index>`. Please refer to [Flink Metric Reporters](https://nightlies.apache.org/flink/flink-docs-release-1.20/docs/deployment/metric_reporters/) for more details.

Additionally, we can check the metric results in the Flink Job WebUI using the metric identifier prefix `<subtask_index>.<operator_name>`.

{{< img src="/fig/operations/metricwebui.png" alt="Metric Web UI" >}}

## Log

The Flink Agents' log system uses Flink's logging framework. For more details, please refer to the [Flink log system documentation](https://nightlies.apache.org/flink/flink-docs-master/docs/deployment/advanced/logging/).

### How to add log in Flink Agents

For adding logs in Java code, you can refer to [Best practices for developers](https://nightlies.apache.org/flink/flink-docs-master/docs/deployment/advanced/logging/#best-practices-for-developers). In Python, you can add logs using `logging`. Here is a specific example:

```python
@action(InputEvent)
@staticmethod
def process_input(event: InputEvent, ctx: RunnerContext) -> None:
    logging.info("Processing input event: %s", event)
    # the action logic
```

### How to check the logs with Flink executor

We can check the log result in the WebUI of Flink Job:

{{< img src="/fig/operations/logwebui.png" alt="Log Web UI" >}}

## Event Log

Currently, the system supports **File-based Event Log** as the default implementation. Future releases will introduce support for additional types of event logs and provide configuration options to let users choose their preferred logging mechanism.

### File Event Log

The **File Event Log** is a file-based event logging system that stores events in structured files within a flat directory. Each event is recorded in **JSON Lines (JSONL)** format, with one JSON object per line.

#### File Structure

The log files follow a naming convention consistent with Flink's logging standards and are stored in a flat directory structure:

```
{baseLogDir}/
├── events-{jobId}-{taskName}-{subtaskId}.log
├── events-{jobId}-{taskName}-{subtaskId}.log
└── events-{jobId}-{taskName}-{subtaskId}.log
```

By default, all File-based Event Logs are stored in the `flink-agents` subdirectory under the system temporary directory (`java.io.tmpdir`). In future versions, we plan to add a configurable parameter to allow users to customize the base log directory, providing greater control over log storage paths and lifecycle management.
