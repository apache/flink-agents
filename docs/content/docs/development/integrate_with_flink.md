---
title: Integrate with Flink
weight: 10
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
Flink Agents is an Agentic AI framework based on Apache Flink. By integrate agent with flink DataStream/Table, Flink Agents can leverage the powerful data processing ability of Flink. 
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
# create input datastream
input_stream = env.from_source(...)

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
// create input datastream
DataStream<YourPojo> inputStream = env.fromSource(...);

// integrate agent with input datastream, and return output datastream
DataStream<Object> outputStream =
        agentsEnv
                .fromDataStream(inputStream, (KeySelector<YourPojo, String>) x::getId)
                .apply(yourAgent)
                .toDataStream();

// consume agent output datastream
outputStream.print();
```
{{< /tab >}}

{{< /tabs >}}

The input `DataStream` must be `KeyedStream`, or user should provide `KeySelector` to tell how to convert the input `DataStream` to `KeyedStream`.

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
input_table = t_env.from_elements(...)
    
output_type = ExternalTypeInfo(RowTypeInfo(
    [BasicTypeInfo.INT_TYPE_INFO()],
    ["result"],
))

schema = (Schema.new_builder().column("result", DataTypes.INT())).build()

output_table = (
    agents_env.from_table(input=input_table, key_selector=MyKeySelector())
    .apply(agent)
    .to_table(schema=schema, output_type=output_type)
)
```
{{< /tab >}}

{{< tab "Java" >}}
```java
Table inputTable = tableEnv.fromValues(...);

// Here the output schema should always be a nested row, of which
// the f0 column is the expected row.
Schema outputSchema =
        Schema.newBuilder()
                .column("f0", DataTypes.ROW(DataTypes.FIELD("result", DataTypes.DOUBLE())))
                .build();

Table outputTable =
        agentsEnv
                .fromTable(
                        inputTable,
                        myKeySelector)
                .apply(agent)
                .toTable(outputSchema);
```
{{< /tab >}}

{{< /tabs >}}


User should provide `KeySelector` in `from_table()` to tell how to convert the input `Table` to `KeyedStream` internally. And provide `Schema` and `TypeInformation` in `to_table()` to tell the output `Table` schema.
{{< hint info >}}
Currently, user should provide both `Schema` and `TypeInformation` when call `to_table()`, we will support only provide one of them in the future.
{{< /hint >}}