---
title: Integrate with Flink
weight: 11
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
Flink Agents is an Agentic AI framework based on Apache Flink. By integrating agents with Flink DataStream/Table, Flink Agents can leverage the powerful data processing ability of Flink.
## From/To Flink DataStream API

First of all, get the flink `StreamExecutionEnvironment` and flink-agents `AgentsExecutionEnvironment`.

{{< tabs "Prepare Agents Execution Environment for DataStream" >}}

{{< tab "Python" >}}
```python
# Set up the Flink streaming environment and the Agents execution environment.
env = StreamExecutionEnvironment.get_execution_environment()
agents_env = AgentsExecutionEnvironment.get_execution_environment(env)
```
{{< /tab >}}

{{< tab "Java" >}}
```java
// Set up the Flink streaming environment and the Agents execution environment.
StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
AgentsExecutionEnvironment agentsEnv =
        AgentsExecutionEnvironment.getExecutionEnvironment(env);
```
{{< /tab >}}

{{< /tabs >}}


Integrate the agent with input `DataStream`, and return the output `DataStream` can be consumed by downstream.

{{< tabs "From/To DataStream" >}}

{{< tab "Python" >}}
```python
from pyflink.common import WatermarkStrategy

# create input datastream
input_stream = env.from_source(
    source=your_source,
    watermark_strategy=WatermarkStrategy.no_watermarks(),
    source_name="your_source_name",
)

# integrate agent with input datastream, and return output datastream
output_stream = (
    agents_env.from_datastream(
        input=input_stream, key_selector=lambda x: x.id
    )
    .apply(your_agent)
    .to_datastream()
)

# consume agent output datastream
output_stream.print()
```
{{< /tab >}}

{{< tab "Java" >}}
```java
import org.apache.flink.api.java.functions.KeySelector;
import org.apache.flink.streaming.api.datastream.DataStream;

// A minimal Flink POJO used as the input element type. A Flink POJO must
// have a public no-arg constructor and public (or getter/setter-accessible) fields.
public static class YourPojo {
    public String id;

    public YourPojo() {}

    public YourPojo(String id) {
        this.id = id;
    }

    public String getId() {
        return id;
    }
}
```

```java
// create input datastream
DataStream<YourPojo> inputStream =
        env.fromElements(new YourPojo("item1"), new YourPojo("item2"));

// integrate agent with input datastream, and return output datastream
DataStream<Object> outputStream =
        agentsEnv
                .fromDataStream(inputStream, (KeySelector<YourPojo, String>) YourPojo::getId)
                .apply(yourAgent)
                .toDataStream();

// consume agent output datastream
outputStream.print();
```
{{< /tab >}}

{{< /tabs >}}

The input `DataStream` must be `KeyedStream`, or user should provide `KeySelector` to tell how to convert the input `DataStream` to `KeyedStream`.

For complete, runnable examples, see [`WorkflowSingleAgentExample.java`](https://github.com/apache/flink-agents/blob/main/examples/src/main/java/org/apache/flink/agents/examples/WorkflowSingleAgentExample.java) (Java) and [`workflow_single_agent_example.py`](https://github.com/apache/flink-agents/blob/main/python/flink_agents/examples/quickstart/workflow_single_agent_example.py) (Python).

## From/To Flink Table API

First of all, get the flink `StreamExecutionEnvironment`, `StreamTableEnvironment`, and flink-agents `AgentsExecutionEnvironment`.

{{< tabs "Prepare Agents Execution Environment for Table" >}}

{{< tab "Python" >}}
```python
# Set up the Flink streaming environment and table environment
env = StreamExecutionEnvironment.get_execution_environment()
t_env = StreamTableEnvironment.create(stream_execution_environment=env)

# Setup flink agents execution environment
agents_env = AgentsExecutionEnvironment.get_execution_environment(env=env, t_env=t_env)
```
{{< /tab >}}

{{< tab "Java" >}}
```java
// Set up the Flink streaming environment and table environment
StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
StreamTableEnvironment tableEnv = StreamTableEnvironment.create(env);

// Setup flink agents execution environment
AgentsExecutionEnvironment agentsEnv =
        AgentsExecutionEnvironment.getExecutionEnvironment(env, tableEnv);
```
{{< /tab >}}

{{< /tabs >}}


Integrate the agent with input `Table`, and return the output `Table` can be consumed by downstream.

{{< tabs "From/To Table" >}}

{{< tab "Python" >}}
```python
from pyflink.common.typeinfo import BasicTypeInfo, ExternalTypeInfo, RowTypeInfo
from pyflink.datastream import KeySelector
from pyflink.table import DataTypes, Schema


# Tell from_table how to derive the key used to convert the input Table to a
# KeyedStream internally.
class MyKeySelector(KeySelector):
    def get_key(self, value):
        return value.id


# create input table (here a small in-memory table; replace with your own source)
input_table = t_env.from_elements(
    [(1, "hello"), (2, "world")],
    ["id", "input"],
)

# The output TypeInformation and Schema must be mutually consistent: both
# describe a single "result" INT column here.
output_type = ExternalTypeInfo(RowTypeInfo(
    [BasicTypeInfo.INT_TYPE_INFO()],
    ["result"],
))

schema = (Schema.new_builder().column("result", DataTypes.INT())).build()

output_table = (
    agents_env.from_table(input=input_table, key_selector=MyKeySelector())
    .apply(your_agent)
    .to_table(schema=schema, output_type=output_type)
)
```
{{< /tab >}}

{{< tab "Java" >}}
```java
import org.apache.flink.api.java.functions.KeySelector;
import org.apache.flink.table.api.DataTypes;
import org.apache.flink.table.api.Schema;
import org.apache.flink.table.api.Table;
import org.apache.flink.types.Row;

// Key selector that extracts the key from each input Row (here, field 0 / the "id" column).
public static class RowKeySelector implements KeySelector<Object, Integer> {
    @Override
    public Integer getKey(Object value) {
        Row row = (Row) value;
        return (Integer) row.getField(0);
    }
}
```

```java
Table inputTable =
        tableEnv.fromValues(
                DataTypes.ROW(
                        DataTypes.FIELD("id", DataTypes.INT()),
                        DataTypes.FIELD("name", DataTypes.STRING()),
                        DataTypes.FIELD("score", DataTypes.DOUBLE())),
                Row.of(1, "Alice", 85.5),
                Row.of(2, "Bob", 92.0),
                Row.of(3, "Charlie", 78.3));

// The agent output is exposed as a single anonymous column named "f0".
// Declare "f0" with the agent's OUTPUT type: a scalar type (e.g. DataTypes.STRING())
// when the agent emits a scalar value, or a nested DataTypes.ROW(...) only when the
// agent emits a composite row.
Schema outputSchema = Schema.newBuilder().column("f0", DataTypes.STRING()).build();

Table outputTable =
        agentsEnv
                .fromTable(inputTable, new RowKeySelector())
                .apply(yourAgent)
                .toTable(outputSchema);
```
{{< /tab >}}

{{< /tabs >}}


User should provide `KeySelector` in `from_table()` to tell how to convert the input `Table` to `KeyedStream` internally.

The arguments required by `to_table()` differ by language:

- **Python**: provide both `Schema` and `TypeInformation` to define the output `Table` schema.
- **Java**: provide only `Schema` (`toTable(Schema)`); `TypeInformation` is not required.

The two languages also name the output columns differently:

- **Python**: the `TypeInformation` passed to `to_table()` is a `RowTypeInfo` whose field names become the output columns, so you name them directly (the `"result"` column above matches `RowTypeInfo([...], ["result"])`).
- **Java**: `toTable(Schema)` exposes the agent output as a single anonymous column named `f0` (internally it calls `StreamTableEnvironment.fromDataStream(DataStream<Object>, schema)`), so the `Schema` must reference `f0` — wrap it in a `ROW(...)` only when the agent emits a composite row.

{{< hint info >}}
In Python, `to_table()` currently requires both `Schema` and `TypeInformation`; we plan to support providing only one of them in the future.
{{< /hint >}}

For complete, runnable examples, see [`WorkflowMultipleAgentExample.java`](https://github.com/apache/flink-agents/blob/main/examples/src/main/java/org/apache/flink/agents/examples/WorkflowMultipleAgentExample.java) (Java) and [`workflow_multiple_agent_example.py`](https://github.com/apache/flink-agents/blob/main/python/flink_agents/examples/quickstart/workflow_multiple_agent_example.py) (Python).
