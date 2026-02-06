---
title: Overview
weight: 1
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

## Introduction

Memory is a system that remembers information about previous interactions. Memory has emerged, and will continue to remain, a core capability of foundation model-based agents.
It underpins long-horizon reasoning, continual adaptation, and effective interaction with complex
environments.<sup>[1](https://arxiv.org/pdf/2512.13564)</sup>

We propose a distributed memory system for flink-agents that mirrors human cognitive processes. The solution implements four memory types: Sensory Memory, Short-term Memory, Long-term Memory and Knowledge, based on their visibility, retention and derivation.

## Memory Types

Flink Agents classifies memory types based on visibility, retention and derivation.

* **visibility**: The recall scope of the memory, i.e., whether it is visible across keys.
  * In Flink Agents, inputs of the agent are partitioned by their keys. This corresponds to how data are partitioned by keys in Flink's Keyed DataStream.
* **retention**: How long the memory will be remembered, within a single run or across multiple runs.
  * An agent run refers to a complete execution of an agent to process an input event. Each record from upstream will trigger a new agent run.
* **derivation**: Whether the retrieved information can be a derived version of the original one.
  * original: The retrieved information is exactly the same as how it was stored.
  * derived: The retrieved information might be derived from the original information.

{{< img src="/fig/memory/memory_types.png" alt="Memory Types" >}}

### Sensory Memory
* **Use Case**: Store temporary data generated during the agent execution which is only needed for a single run. For example, tool call context, or the data users want to pass to other agent actions.
* **Characteristics**: Isolation between agent runs, which means the memory will be automatically cleaned up by the framework when an agent run is completed.

For more details, see [Sensory & Short-term Memory]({{< ref "docs/development/memory/sensory_and_short_term_memory" >}}).

### Short-Term Memory
* **Use Case**: Store data generated during the agent execution. 
  * Compared to sensory memory, the lifecycle of stored data can across multiple runs.
  * Compared to long-term memory, user need retrieve all the original data they have written to the memory.
* **Characteristics**: 
  * The lifecycle of the stored data can across multiple runs. 
  * Complete original data retrieval.

For more details, see [Sensory & Short-Term Memory]({{< ref "docs/development/memory/sensory_and_short_term_memory" >}}).

### Long-Term Memory
* **Use Case**: Store data generated during the agent execution, but compared to short-term memory,
the data may expand rapidly as the agent execution, which requires compaction to provide concise and highly related context.
* **Characteristics**: 
  * Support for compacting similar items.
  * Support for semantic search.

For more details, see [Long-Term Memory]({{< ref "docs/development/memory/long_term_memory" >}}).

## Comparison

| Feature               | Sensory Memory                             | Short-Term Memory                             | Long-Term Memory                              |
|-----------------------|--------------------------------------------|-----------------------------------------------|-----------------------------------------------|
| **Retention**         | Single Run                                 | Multiple Runs                                 | Multiple Runs                                 |
| **Visibility**        | Single-Key                                 | Single-Key                                    | Single-Key                                    |
| **Derivation**        | Original                                   | Original                                      | Derived                                       |
| **Storage**           | Flink State                                | Flink State                                   | External Vector Store                         |
| **Access Pattern**    | Key-Value / Nested Objects                 | Key-Value / Nested Objects                    | Semantic Search                               |
| **Use Case**          | Temporary execution states of an agent run | Exact information shared across multiple runs | Large, compactable and searchable information |
| **Types of Contents** | Any primitive/object                       | Any primitive/object                          | String, ChatMessage                           |