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

Rerank models synchronously score and order candidate documents against a
single query, returning the most relevant hits first. Use a rerank model to
refine candidates retrieved from a
[vector store]({{< ref "docs/development/vector-search/vector_stores" >}}) or
to rank text and multimodal candidate lists directly.

Each `RERANK_MODEL` resource (`BaseRerankModel`) binds a single model name and
owns its service connection. Calls are synchronous and return a `RerankResult`.

See [Built-in Rerank Model Providers]({{< ref "docs/development/rerank_models/providers" >}})
for available implementations, parameters, and provider-specific input limits.

## Getting started

### Register a rerank model

Register a `RERANK_MODEL` resource with a descriptor for your chosen
implementation. The example below uses the built-in DashScope implementation;
replace the class and parameters with those of any built-in or custom provider
listed in [Built-in Rerank Model Providers]({{< ref "docs/development/rerank_models/providers" >}}).

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
for registration scopes and
[YAML Resources]({{< ref "docs/development/create-agent/yaml#resources" >}})
for YAML declarations.

### Rank candidates

Inside an Action, retrieve the registered model by name and
`ResourceType.RERANK_MODEL`, then pass a query block and candidate blocks to
`rerank()`:

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
top_hit = result.results[0]  # top_hit.index, top_hit.relevance_score, top_hit.document
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

## Inputs, results, and parameters

- **Inputs**: `rerank()` accepts a query block (`TextBlock` or `ImageBlock`)
  and an ordered sequence of candidate document blocks (`TextBlock`,
  `ImageBlock`, or `VideoBlock`). See
  [Multimodal Messages]({{< ref "docs/development/calling-llms/chat#message-content-blocks" >}})
  for constructing blocks. Which modalities and media sources (URL vs. inline
  Base64) are accepted depends on the provider and model; see
  [Built-in Rerank Model Providers]({{< ref "docs/development/rerank_models/providers" >}}).
- **Results**: `result.results` (Java: `result.getResults()`) returns
  `RerankHit` items ordered from highest to lowest `relevance_score`. Each
  hit's `index` is the candidate's zero-based position in the request's
  `documents` list. `hit.document` (`hit.getDocument()`) is populated with the
  original input block only when `return_documents` is enabled; otherwise it is
  `None` (`null`). `RerankResult` also includes optional `usage` and
  `request_id`.
- **Call options and precedence**: In Python, `rerank()` accepts explicit
  `top_n` and `return_documents` arguments, a `model_parameters` mapping for
  additional provider options, and an optional per-call `timeout`. In Java,
  pass model parameters (including `top_n` and `return_documents`) in the
  `modelParameters` map along with an optional `Duration timeout`. Precedence
  is:

```text
call explicit (Python: top_n / return_documents) > call model_parameters > resource model_parameters > provider default
```

## Lifecycle

Flink Agents opens registered rerank models before use and closes them when
their resource scope ends. Actions should leave lifecycle management to the
framework.

For standalone Python use outside a Flink job, construct the model, call
`open()` before calling `rerank()`, and call `close()` in a `finally` block
when finished.

## Using cross-language providers

Rerank models support Java-to-Python cross-language use: Java Actions can
invoke Python implementations through `PythonResourceDescriptor` or YAML
`type: python`. See
[Cross-Language Resources]({{< ref "docs/development/create-agent/resources#cross-language-resources" >}})
for descriptor selection and runtime requirements.

## Custom providers

{{< hint warning >}}
The custom provider APIs are experimental and unstable, subject to incompatible
changes in future releases.
{{< /hint >}}

To integrate another reranking service, extend `BaseRerankModel` (Python:
`flink_agents.api.rerank_models.rerank_model.BaseRerankModel`; Java:
`org.apache.flink.agents.api.rerank.BaseRerankModel`), manage client lifecycle
in `open()` and `close()`, and implement `rerank(...)` to return a
`RerankResult`. Keep `rerank(...)` side-effect-free with respect to Agent
memory and events so it can run safely inside durable executions.

[Built-in Rerank Model Providers]({{< ref "docs/development/rerank_models/providers" >}}).
