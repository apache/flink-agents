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

See [Built-in Rerank Model Providers]({{< ref "docs/development/rerank_models/providers" >}})
for available implementations and provider options.

## Quick start

### Register a rerank model

Declare a `RERANK_MODEL` resource with `@rerank_model` in Python, `@RerankModel`
in Java, or `rerank_models` in YAML. The example below uses DashScope; replace it
with any provider from
[Built-in Rerank Model Providers]({{< ref "docs/development/rerank_models/providers" >}}):

{{< tabs "Register a rerank model" >}}
{{< tab "Python" >}}
```python
from flink_agents.api.agents.agent import Agent
from flink_agents.api.decorators import rerank_model
from flink_agents.api.resource import ResourceDescriptor, ResourceName


class SearchAgent(Agent):
    @rerank_model
    @staticmethod
    def ranker() -> ResourceDescriptor:
        return ResourceDescriptor(
            clazz=ResourceName.RerankModel.DASHSCOPE,
            model="qwen3-vl-rerank",
        )
```
{{< /tab >}}
{{< tab "Java" >}}
```java
import org.apache.flink.agents.api.agents.Agent;
import org.apache.flink.agents.api.annotation.RerankModel;
import org.apache.flink.agents.api.resource.PythonResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceName;

public class SearchAgent extends Agent {
    @RerankModel
    public static ResourceDescriptor ranker() {
        return PythonResourceDescriptor.Builder.newBuilder(ResourceName.RerankModel.Python.DASHSCOPE)
                .addInitialArgument("model", "qwen3-vl-rerank")
                .build();
    }
}
```
{{< /tab >}}
{{< tab "YAML" >}}
```yaml
rerank_models:
  - name: ranker
    clazz: dashscope
    type: python
    model: qwen3-vl-rerank
```
{{< /tab >}}
{{< /tabs >}}

To register a rerank model as a shared resource through the execution
environment instead, call `agents_env.add_resource("ranker", ResourceType.RERANK_MODEL, descriptor)`
in Python (or `agentsEnv.addResource(...)` in Java). See
[Registering Resources]({{< ref "docs/development/create-agent/resources#registering-resources" >}})
and [YAML Resources]({{< ref "docs/development/create-agent/yaml#resources" >}}).

### Use the rerank model

Inside an Action, retrieve the model via `ResourceType.RERANK_MODEL` and call
`rerank()` with a query block and candidate blocks:

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
    timeout=30.0,
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

- **Inputs**: `rerank()` accepts a query (`TextBlock` or `ImageBlock`) and a
  non-empty list of candidate documents (`TextBlock`, `ImageBlock`, or
  `VideoBlock`). See
  [Multimodal Messages]({{< ref "docs/development/calling-llms/chat#message-content-blocks" >}})
  for constructing blocks.
- **Results**: `rerank()` returns a `RerankResult` whose `results`
  (`getResults()`) list hits ordered from highest to lowest `relevance_score`,
  each with its zero-based input `index` and optional `document` when
  `return_documents` is enabled, alongside optional `usage` and `request_id`.
- **Per-call overrides**: Pass `model_parameters` and `timeout` (`float` seconds
  in Python, `Duration` in Java) to override resource defaults for a single call
  (Python also exposes `top_n` and `return_documents` keyword arguments). See
  [Built-in Rerank Model Providers]({{< ref "docs/development/rerank_models/providers" >}})
  for provider-specific parameters and defaults.

## Custom providers

{{< hint warning >}}
The custom provider APIs are experimental and unstable, subject to incompatible
changes in future releases.
{{< /hint >}}

To integrate another reranking service, extend `BaseRerankModel`, manage the
client in `open()` and `close()`, and implement `rerank(...)` to return a
`RerankResult`.
