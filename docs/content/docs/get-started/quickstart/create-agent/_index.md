---
title: "Build an Agent"
weight: 1
type: docs
bookCollapseSection: true
aliases:
  - /docs/get-started/quickstart/workflow_agent/
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

An Agent in Flink-Agents is an agent whose reasoning and behavior are organized as a directed workflow of modular steps, called actions, connected by events. This design is inspired by the need to orchestrate complex, multi-stage tasks in a transparent, extensible, and data-centric way, leveraging Apache Flink's streaming architecture.

This quickstart introduces two small, progressive streaming examples that demonstrate how to build LLM-powered workflows with Flink Agents:

- **Review Analysis**: Processes a stream of product reviews and uses a single agent to extract a rating (1–5) and unsatisfied reasons from each review.

- **Product Improvement Suggestions**: Builds on the first example by aggregating per-review analysis in windows to produce product-level summaries (score distribution and common complaints), then applies a second agent to generate concrete improvement suggestions for each product.

Together, these examples show how to build a multi-agent workflow with Flink Agents and run it on a Flink standalone cluster.


- [Build Your First Agent]({{< ref "docs/get-started/quickstart/create-agent/single-agent" >}})
- [Connect Multiple Agents]({{< ref "docs/get-started/quickstart/create-agent/multiple-agents" >}})
