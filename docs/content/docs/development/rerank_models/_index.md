---
title: "Rerank Models"
weight: 10
type: docs
---
<!--
Licensed to the Apache Software Foundation (ASF) under one
or more contributor license agreements. See the NOTICE file
distributed with this work for additional information
regarding copyright ownership. The ASF licenses this file
to you under the Apache License, Version 2.0 (the
"License"); you may not use this file except in compliance
with the License. You may obtain a copy of the License at

  http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing,
software distributed under the License is distributed on an
"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
KIND, either express or implied. See the License for the
specific language governing permissions and limitations
under the License.
-->

# Rerank Models

## Overview

Use a rerank model to score and order candidate documents by relevance to a
query—for example, refining results retrieved from a
[vector store]({{< ref "docs/development/vector-search/vector_stores" >}}) or
ranking mixed text, image, and video candidates directly.

Register a `RERANK_MODEL` resource for the model you want to call, then pass a
query and candidate list to `rerank()` inside an Action. See
[Built-in Rerank Model Providers]({{< ref "docs/development/rerank_models/providers" >}})
for available implementations and provider options.

## Quick start

### 1. Register a rerank model

Register a `RERANK_MODEL` resource with your chosen provider descriptor. The
example below uses DashScope; replace it with any provider from
[Built-in Rerank Model Providers]({{< ref "docs/development/rerank_models/providers" >}}):

{{< tabs "Register a rerank model" >}}
{{< tab "Python" >}}
```python
from flink_agents.api.resource import ResourceDescriptor, ResourceName, ResourceType

agents_env.add_resource(
    "ranker",
    ResourceType.RERANK_MODEL,
    ResourceDescriptor(
        clazz=ResourceName.RerankModel.DASHSCOPE,
        model="qwen3-vl-rerank",
    ),
)
```
{{< /tab >}}
{{< tab "Java" >}}
```java
import org.apache.flink.agents.api.resource.PythonResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceName;
import org.apache.flink.agents.api.resource.ResourceType;

agentsEnv.addResource(
        "ranker",
        ResourceType.RERANK_MODEL,
        PythonResourceDescriptor.Builder.newBuilder(ResourceName.RerankModel.Python.DASHSCOPE)
                .addInitialArgument("model", "qwen3-vl-rerank")
                .build());
```
{{< /tab >}}
{{< /tabs >}}

You can also declare a rerank model using `@rerank_model` in Python,
`@RerankModel` in Java, or the YAML `rerank_models` section. See
[Registering Resources]({{< ref "docs/development/create-agent/resources#registering-resources" >}})
and [YAML Resources]({{< ref "docs/development/create-agent/yaml#resources" >}}).

### 2. Rank candidates

Inside an Action, retrieve the model and call `rerank()` with a query block and
candidate blocks:

{{< tabs "Call a rerank model" >}}
{{< tab "Python" >}}
```python
from typing import cast

from flink_agents.api.chat_message import ImageBlock, TextBlock, VideoBlock
from flink_agents.api.rerank_models.rerank_model import BaseRerankModel
from flink_agents.api.resource import ResourceType

ranker = cast(
    BaseRerankModel,
    ctx.get_resource("ranker", ResourceType.RERANK_MODEL),
)
result = ranker.rerank(
    TextBlock(text="Which result shows the architecture diagram?"),
    [
        TextBlock(text="Stream processing overview."),
        ImageBlock.from_url("image/png", "https://example.com/architecture.png"),
        VideoBlock.from_url("video/mp4", "https://example.com/demo.mp4"),
    ],
    top_n=2,
    return_documents=True,
    model_parameters={"fps": 0.1},
)
top_hit = result.results[0]  # index, relevance_score, document
```
{{< /tab >}}
{{< tab "Java" >}}
```java
import org.apache.flink.agents.api.chat.messages.DataContentBlock;
import org.apache.flink.agents.api.chat.messages.ImageBlock;
import org.apache.flink.agents.api.chat.messages.TextBlock;
import org.apache.flink.agents.api.chat.messages.VideoBlock;
import org.apache.flink.agents.api.rerank.BaseRerankModel;
import org.apache.flink.agents.api.rerank.RerankHit;
import org.apache.flink.agents.api.rerank.RerankResult;
import org.apache.flink.agents.api.resource.ResourceType;

import java.time.Duration;
import java.util.List;
import java.util.Map;

BaseRerankModel ranker =
        (BaseRerankModel) ctx.getResource("ranker", ResourceType.RERANK_MODEL);
List<DataContentBlock> candidates =
        List.of(
                TextBlock.of("Stream processing overview."),
                ImageBlock.fromUrl("image/png", "https://example.com/architecture.png"),
                VideoBlock.fromUrl("video/mp4", "https://example.com/demo.mp4"));
RerankResult result =
        ranker.rerank(
                TextBlock.of("Which result shows the architecture diagram?"),
                candidates,
                Map.of("top_n", 2, "return_documents", true, "fps", 0.1),
                Duration.ofSeconds(30));
RerankHit topHit = result.getResults().get(0);
```
{{< /tab >}}
{{< /tabs >}}

## Inputs, results, and options

- **Inputs**: `rerank()` accepts a query (`TextBlock` or `ImageBlock`) and a
  non-empty list of candidate documents (`TextBlock`, `ImageBlock`, or
  `VideoBlock`). See
  [Multimodal Messages]({{< ref "docs/development/calling-llms/chat#message-content-blocks" >}})
  for constructing blocks, and
  [Built-in Rerank Model Providers]({{< ref "docs/development/rerank_models/providers" >}})
  for what each provider and model supports.
- **Results**: `rerank()` returns a `RerankResult` whose `results`
  (`getResults()`) list hits ordered from highest to lowest `relevance_score`,
  with each hit's zero-based `index` in the input list and optional `document`
  when `return_documents` is enabled, along with optional `usage` and
  `request_id`.
- **Options**: Configured at two levels, with per-call values overriding
  resource defaults:
  - **Resource level (`RERANK_MODEL` descriptor)**: Connection and client
    settings (`api_key`, `region`, `base_url`, `request_timeout`), `model`, and
    default `model_parameters` (`top_n`, `return_documents`, `instruct`, `fps`).
  - **Per-call level (`rerank()`)**: Request-specific overrides for
    `model_parameters` (Python also exposes `top_n` and `return_documents` as
    keyword arguments) and `timeout` (`timeout=30` in Python or
    `Duration.ofSeconds(30)` in Java, overriding `request_timeout`).

## Lifecycle and cross-language use

- **Lifecycle**: Flink Agents opens and closes registered rerank models
  automatically. For standalone Python scripts, call `open()` before use and
  `close()` in a `finally` block.
- **Cross-language**: Java Agents can use Python rerank models via
  `PythonResourceDescriptor` or YAML `type: python`. See
  [Cross-Language Resources]({{< ref "docs/development/create-agent/resources#cross-language-resources" >}}).

## Custom providers

{{< hint warning >}}
The custom provider APIs are experimental and unstable, subject to incompatible
changes in future releases.
{{< /hint >}}

To integrate another reranking service, extend `BaseRerankModel`, manage the
client in `open()` and `close()`, and implement `rerank(...)` to return a
`RerankResult`.
