---
title: "Connect Multiple Agents"
weight: 2
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

## Overview

This example builds on [Build Your First Agent]({{< ref "docs/get-started/quickstart/create-agent/single-agent" >}}). It connects two Agents in one Flink job, aggregating per-review analysis in windows and applying a second Agent to generate product improvement suggestions.

Reuse the [environment and connection setup]({{< ref "docs/get-started/quickstart/create-agent/single-agent#prepare-agents-execution-environment" >}}) and [example preparation]({{< ref "docs/get-started/quickstart/create-agent/single-agent#run-the-example" >}}) from the first walkthrough.

The code for the `ProductSuggestionAgent`, which is used to generate product improvement suggestions based on the aggregated analysis results, is similar to the `ReviewAnalysisAgent`.

### Read Input via the Table API

The multiple-agent example reads product reviews through the Flink Table API instead of a DataStream. Because Table rows arrive as `Row` (Java) / `dict` (Python) instead of a `ProductReview` POJO, it uses a dedicated `TableReviewAnalysisAgent` — identical to `ReviewAnalysisAgent` except its `process_input` action reads the `id` and `review` columns out of the row, and it ships a key selector that extracts the key from that row.

{{< tabs "Create the Table Agent" >}}

{{< tab "Python" >}}
```python
class TableKeySelector(KeySelector):
    """Extract the partition key from a Table row (dict)."""

    def get_key(self, value: Any) -> str:
        return str(value["id"])


class TableReviewAnalysisAgent(Agent):
    # prompt / tool / chat_model_setup are identical to ReviewAnalysisAgent.

    @action(InputEvent.EVENT_TYPE)
    @staticmethod
    def process_input(event: Event, ctx: RunnerContext) -> None:
        # Table input arrives as a dict keyed by column name, not a POJO.
        input_dict = InputEvent.from_event(event).input
        product_id = str(input_dict["id"])
        review_text = str(input_dict["review"])
        ...
```
{{< /tab >}}

{{< tab "Java" >}}
```java
public class TableReviewAnalysisAgent extends Agent {

    /** Extract the partition key from a Table Row. */
    public static class RowKeySelector implements KeySelector<Object, String> {
        @Override
        public String getKey(Object value) {
            return (String) ((Row) value).getField("id");
        }
    }

    // prompt / tool / chat model setup are identical to ReviewAnalysisAgent.

    @Action(EventType.InputEvent)
    public static void processInput(Event event, RunnerContext ctx) {
        // Table input arrives as a Row keyed by column name, not a POJO.
        Row row = (Row) InputEvent.fromEvent(event).getInput();
        String productId = (String) row.getField("id");
        String reviewText = (String) row.getField("review");
        ...
    }
}
```
{{< /tab >}}

{{< /tabs >}}

Register the input file as a table and feed it to the agent with `from_table` / `fromTable`, providing the key selector:

{{< tabs "Integrate via Table API" >}}

{{< tab "Python" >}}
```python
input_table = t_env.from_path("product_reviews")

review_analysis_res_stream = (
    agents_env.from_table(input=input_table, key_selector=TableKeySelector())
    .apply(TableReviewAnalysisAgent())
    .to_datastream(ProductReviewAnalysisRes)
)
```
{{< /tab >}}

{{< tab "Java" >}}
```java
Table inputTable = tableEnv.from("product_reviews");

DataStream<ProductReviewAnalysisRes> reviewAnalysisResStream =
        agentsEnv
                .fromTable(inputTable, new TableReviewAnalysisAgent.RowKeySelector())
                .apply(new TableReviewAnalysisAgent())
                .toDataStream(ProductReviewAnalysisRes.class);
```
{{< /tab >}}

{{< /tabs >}}

See [Integrate with Flink]({{< ref "docs/development/integrate_with_flink#fromto-flink-table-api" >}}) for the full Table API integration reference.


## Run the Example

### Submit Flink Agents Job to Standalone Flink Cluster

#### Submit to Flink Cluster

{{< tabs "Submit to Flink Cluster" >}}

{{< tab "Python" >}}
```bash
export PYTHONPATH=$(python -c 'import sysconfig; print(sysconfig.get_paths()["purelib"])')

# Run product suggestion example
$FLINK_HOME/bin/flink run -py ./flink-agents/python/flink_agents/examples/quickstart/workflow_multiple_agent_example.py
# or submit the example python file in installed flink-agents wheel
$FLINK_HOME/bin/flink run -py  $PYTHONPATH/flink_agents/examples/quickstart/workflow_multiple_agent_example.py
```
{{< /tab >}}

{{< tab "Java" >}}
```bash
# Run product suggestion example
$FLINK_HOME/bin/flink run -c org.apache.flink.agents.examples.WorkflowMultipleAgentExample ./flink-agents/examples/target/flink-agents-examples-$VERSION.jar
```
{{< /tab >}}

{{< /tabs >}}

Now you should see a Flink job submitted to the Flink Cluster in Flink web UI [localhost:8081](
localhost:8081)

After a few minutes, you can check for the output in the TaskManager output log.
