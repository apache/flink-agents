---
title: Integrate with Flink
weight: 13
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

# A single output-type declaration: the Schema's physical columns give the
# output row type, and each agent output element is adapted into a matching row.
schema = Schema.new_builder().column("result", DataTypes.INT()).build()

output_table = (
    agents_env.from_table(input=input_table, key_selector=MyKeySelector())
    .apply(your_agent)
    .to_table(schema=schema)
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

// Declare the output columns. Each agent output element is adapted into a row by
// matching columns by name (from a POJO's getters/public fields, a Row, or a Map);
// a scalar output maps to a single-column schema.
Schema outputSchema =
        Schema.newBuilder()
                .column("name", DataTypes.STRING())
                .column("score", DataTypes.DOUBLE())
                .build();

Table outputTable =
        agentsEnv
                .fromTable(inputTable, new RowKeySelector())
                .apply(yourAgent)
                .toTable(outputSchema);
```
{{< /tab >}}

{{< /tabs >}}


User should provide `KeySelector` in `from_table()` to tell how to convert the input `Table` to `KeyedStream` internally.

## Typed outputs

`to_datastream()` / `toDataStream()` return an unrestricted stream whose elements are whatever the agent emitted (`Object` in Java). To give the output a concrete type, pass the type straight to the terminal: `to_datastream(output_type)` / `toDataStream(TypeInformation)` materialize a typed stream, and `to_table(output_type=...)` / `toTable(TypeInformation)` materialize a typed table. The unrestricted call stays available and unchanged:

{{< tabs "Typed outputs" >}}

{{< tab "Python" >}}
```python
builder = agents_env.from_datastream(input_stream, key_selector).apply(your_agent)

raw = builder.to_datastream()   # unrestricted: whatever the agent emitted

# Pass the output type to the terminal to materialize a typed stream or table.
typed = builder.to_datastream(ReviewOutput)          # stream of validated ReviewOutput
table = builder.to_table(output_type=ReviewOutput)   # schema derived from the type
# Or pass both: the Schema drives the physical table and is cross-checked
# against the declared type.
both = builder.to_table(review_schema, ReviewOutput)
```
{{< /tab >}}

{{< tab "Java" >}}
```java
AgentBuilder builder =
        agentsEnv.fromDataStream(inputStream, keySelector).apply(yourAgent);

DataStream<Object> raw = builder.toDataStream();   // unrestricted

// Pass the output type to the terminal to materialize a typed stream or table.
DataStream<ReviewOutput> typed = builder.toDataStream(ReviewOutput.class);
Table fromType = builder.toTable(ReviewOutput.class);   // schema derived from the type
```
{{< /tab >}}

{{< /tabs >}}

For the `Table` terminal:

- Calling `to_table(output_type=...)` / `toTable(TypeInformation)` with no schema derives the physical columns from the declared type, so a POJO's fields (or a structured Python type's fields) become columns.
- In Python, `to_table(schema, output_type)` accepts both: the `Schema` drives the physical table, preserving Table-domain information such as a primary key, computed columns, metadata columns, or a watermark, and is cross-checked against the declared type, raising if the two describe different row types. At least one of the two is required. In Java the schema-only `toTable(Schema)` and the type-only `toTable(TypeInformation)` are separate overloads.

In Java, `toDataStream(Class)` / `toTable(Class)` are conveniences that derive the `TypeInformation` from the class; the `TypeInformation` overloads remain for generic or custom types.

The schema-only `to_table(schema=...)` / `toTable(Schema)` stays available when you have a physical schema but no output type to declare.

Each agent output element is adapted to the declared row type by matching columns by name — from a POJO's getters or public fields, a `Row`, or a `Map`. A scalar output maps to a single-column row.
