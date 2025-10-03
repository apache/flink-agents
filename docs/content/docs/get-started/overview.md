---
title: 'Overview'
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

## What is Flink Agents?

**Apache Flink Agents** is a brand-new sub-project from the Apache Flink community, providing an **open-source framework for building event-driven streaming agents**.

Building on Flink's battle-tested streaming engine, Apache Flink Agents inherits **distributed, at-scale, fault-tolerant structured data processing and mature state management**, and adds first-class abstractions for Agentic AI building blocks and functionalities - **large language models (LLMs)**, **prompts**, **tools memory**, **dynamic orchestration**, **observability**, and more.

## Features

The key features of Apache Flink Agents include:
- **Massive Scale and Millisecond Latency**: Processes massive-scale event streams in real time, leveraging Flink's distributed processing engine.
- **Seamless Data and AI Integration**: Agents interact directly with Flink's DataStream and Table APIs for input and output, enabling a smooth integration of structured data processing and semantic AI capabilities within Flink.
- **Exactly-Once Action Consistency**: Ensures exactly-once consistency for agent actions and their side effects by integrating Flink's checkpointing with an external write-ahead log.
- **Familiar Agent Abstractions**: Leverages well-known AI agent concepts, making it easy for developers experienced with agent-based systems to quickly adopt and build on Apache Flink Agents without a steep learning curve.
- **Multi-Language Supports**: Provides native APIs in both Python and Java, enabling seamless integration into diverse development environments and allowing teams to use their preferred programming language.
- **Rich Ecosystem**: Natively integrates mainstream LLMs, vector stores from diverse providers, and tools or prompts hosted on MCP servers into your agents, while enabling customizable extensions.
- **Observability**: Adopts an event-centric orchestration approach, where all agent actions are connected and controlled by events, enabling observation and understanding of agent behavior through the event log.

## Getting Started

To get started with Apache Flink Agents, you can checkout the following quickstarts:

- [Workflow Agent Quickstart]({{< ref "docs/get-started/quickstart/workflow_agent" >}})
- [ReAct Agent Quickstart]({{< ref "docs/get-started/quickstart/react_agent" >}})
