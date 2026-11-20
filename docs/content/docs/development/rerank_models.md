---
title: Rerank Models
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

Rerank models synchronously score and order candidate documents against a single
query, returning the most relevant hits first. Use a rerank model to refine
candidates retrieved from a [vector store]({{< ref "docs/development/vector-search/vector_stores" >}})
or to rank text, image, and video results directly.

Each `RERANK_MODEL` resource binds one model name and owns its client
connection. Calls are synchronous and return a `RerankResult`.

## Register a rerank model

Alibaba Cloud Model Studio (DashScope) is built in as a Python implementation,
`DashScopeRerankModel`. Java Actions can use it through
[Cross-Language Resources]({{< ref "docs/development/create-agent/resources#cross-language-resources" >}}).
Set `DASHSCOPE_API_KEY` in the runtime environment, then register a descriptor
with the model you want to call:

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

Use a text model such as `qwen3-rerank` for text-only ranking, or a multimodal
model such as `qwen3-vl-rerank` when any query or document is an image or video.
You can also declare the descriptor using `@rerank_model` in Python,
`@RerankModel` in Java, or the YAML `rerank_models` section. See
[Registering Resources]({{< ref "docs/development/create-agent/resources#registering-resources" >}})
for registration scopes and
[YAML Resources]({{< ref "docs/development/create-agent/yaml#resources" >}})
for YAML declarations.

## Rank candidates

Inside an Action, retrieve the registered model by name and
`ResourceType.RERANK_MODEL`, then pass a query block and candidate document
blocks to `rerank()`:

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

### Inputs and results

See [Multimodal Messages]({{< ref "docs/development/calling-llms/chat#message-content-blocks" >}})
for constructing content blocks. DashScope accepts the following input forms:

| Input | Supported blocks | Supported sources |
|---|---|---|
| Query | `TextBlock`, `ImageBlock` | Non-empty text; image HTTP(S) URL or inline Base64 |
| Document | `TextBlock`, `ImageBlock`, `VideoBlock` | Non-empty text; image HTTP(S) URL or inline Base64; video **HTTP(S) URL only** |

- `result.results` (Java: `result.getResults()`) is ordered from highest to
  lowest `relevance_score`. Each hit's `index` is the candidate's zero-based
  position in the request's `documents` list.
- `hit.document` (`hit.getDocument()`) is populated only when
  `return_documents` is `True`, returning the original input block; otherwise it
  is `None` (`null`). `RerankResult` also includes `usage` and `request_id`.
- See the [DashScope Rerank API](https://help.aliyun.com/en/model-studio/text-rerank-api)
  for available models, limits, and model-specific parameters such as `instruct`
  and `fps`.

## Parameters and precedence

### DashScopeRerankModel Parameters

| Parameter | Type | Default | Description |
|---|---|---|---|
| `model` | str | Required | Rerank model name (for example, `qwen3-rerank` or `qwen3-vl-rerank`). |
| `api_key` | str | `$DASHSCOPE_API_KEY` | DashScope API key, resolved when the resource opens. |
| `workspace` | str | `None` | Model Studio workspace ID. With `region`, selects `https://{workspace}.{region}.maas.aliyuncs.com`. |
| `region` | str | `"cn-beijing"` | DashScope gateway region. |
| `base_url` | str | `None` | Custom API root (including its path) replacing the SDK-derived address. |
| `request_timeout` | float | `60.0` | HTTP timeout in seconds for a single request. |
| `model_parameters` | dict | `{}` | Default model parameters (for example, `top_n`, `return_documents`, `instruct`, or `fps`). |

### Call-level overrides

In Python, `rerank()` accepts explicit `top_n` and `return_documents` arguments,
a `model_parameters` mapping for additional provider options (such as `instruct`
or `fps`), and an optional per-call `timeout` overriding `request_timeout`. In
Java, pass model parameters (including `top_n`, `return_documents`, `instruct`,
and `fps`) in the `modelParameters` map along with an optional `Duration timeout`.

Parameter precedence is:

```text
call explicit (Python: top_n / return_documents) > call model_parameters > resource model_parameters > provider default
```

## Run the example

After [setting up PyFlink]({{< ref "docs/get-started/setup" >}}), activate your
Python environment, set `DASHSCOPE_API_KEY`, and run
{{< gh_link file="python/flink_agents/examples/rerank_models/search_ranking_example.py" name="`search_ranking_example.py`" >}}:

```shell
DASHSCOPE_API_KEY=... python -m flink_agents.examples.rerank_models.search_ranking_example
```

The job registers `qwen3-vl-rerank`, ranks two questions against a text, image,
and video candidate, and prints the top hit's index and content block type.

## Lifecycle

Flink Agents opens registered rerank models before use and closes them when
their resource scope ends. Actions should leave lifecycle management to the
framework.

For standalone Python use, construct `DashScopeRerankModel`, call `open()`
before calling `rerank()`, and call `close()` in a `finally` block when
finished.
