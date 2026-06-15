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

**Apache Flink Agents** is a streaming **Agent OS** for enterprise, production-grade scenarios, built as a sub-project of the Apache Flink community. It brings AI agents into the Flink streaming pipeline - an agent becomes a first-class operator in your real-time datastream, making AI decisions in the flow of live events rather than in response to human prompts.

Like any Agent OS, it manages the core building blocks of an agent - orchestration, context, memory, skills, and tool/MCP invocation. Built on Flink's battle-tested streaming engine, it additionally inherits **distributed, at-scale, fault-tolerant** processing and mature state management, so agents run **event-driven, distributed, and reliable** at production scale.

## Features

The key features of Apache Flink Agents include:
- **Massive Scale and Millisecond Latency**: Processes massive-scale event streams in real time, leveraging Flink's distributed processing engine.
- **Seamless Data and AI Integration**: Agents interact directly with Flink's DataStream and Table APIs for input and output, enabling a smooth integration of structured data processing and semantic AI capabilities within Flink.
- **Reliable and Exactly-Once Execution**: Ensures exactly-once consistency for agent actions and their side effects by integrating Flink's checkpointing with an external write-ahead log, and supports durable execution - optionally with reconcilers to reconcile in-flight side effects upon failure recovery - so agents run reliably without human supervision.
- **Familiar Agent Abstractions**: Leverages well-known AI agent concepts - skills, short/long-term memory, prompts, tools, and dynamic orchestration - making it easy for developers experienced with agent-based systems to quickly adopt and build on Apache Flink Agents without a steep learning curve.
- **Multi-Language Supports**: Provides native APIs in Python, Java, and a declarative YAML API, enabling seamless integration into diverse development environments and allowing teams to use their preferred programming style. You can even mix languages, authoring actions, tools, and events in one and running them in an agent built in the other. For guidance on choosing Java or Python, see [Should I choose Java or Python?]({{< ref "docs/faq/faq#q3-should-i-choose-java-or-python" >}}).
- **Rich Ecosystem**: Natively integrates mainstream LLMs, vector stores from diverse providers, and tools or prompts hosted on MCP servers into your agents, while enabling customizable extensions.
- **Observability**: Adopts an event-centric orchestration approach, where all agent actions are connected and controlled by events, enabling observation and understanding of agent behavior through the event log.

## Getting Started

To get started with Apache Flink Agents, you can checkout the following quickstarts:

- [Workflow Agent Quickstart]({{< ref "docs/get-started/quickstart/workflow_agent" >}})
- [ReAct Agent Quickstart]({{< ref "docs/get-started/quickstart/react_agent" >}})
- [Skills Agent Quickstart]({{< ref "docs/get-started/quickstart/skills_agent" >}})
- [YAML Agent Quickstart]({{< ref "docs/get-started/quickstart/yaml_agent" >}})
