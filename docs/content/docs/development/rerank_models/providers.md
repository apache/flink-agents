---
title: "Built-in Rerank Model Providers"
weight: 1
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

## Built-in Providers

For general usage and result fields, see
[Rerank Models]({{< ref "docs/development/rerank_models" >}}). For registration
methods and scopes, see
[Resources]({{< ref "docs/development/create-agent/resources#registering-resources" >}}).

### Alibaba Cloud Model Studio (DashScope) {#alibaba-cloud-model-studio-dashscope}

Connects to Alibaba Cloud Model Studio (Bailian) through `DashScopeRerankModel`
for text and multimodal reranking.

{{< hint info >}}
The DashScope rerank model currently has a Python implementation only. To use
it from Java Agents, see
[Cross-Language Resources]({{< ref "docs/development/create-agent/resources#cross-language-resources" >}}).
{{< /hint >}}

#### Prerequisites

1. Get an API key from [Alibaba Cloud Model Studio](https://help.aliyun.com/en/model-studio/get-api-key).
2. Set `DASHSCOPE_API_KEY`, or pass `api_key` in the resource descriptor.

#### Supported Inputs

| Input | Supported blocks | Supported sources |
|---|---|---|
| Query | `TextBlock`, `ImageBlock` | Text; image HTTP(S) URL or Base64 |
| Document | `TextBlock`, `ImageBlock`, `VideoBlock` | Text; image HTTP(S) URL or Base64; video **HTTP(S) URL only** |

Use a text model such as `qwen3-rerank` for text-only ranking, or a multimodal
model such as `qwen3-vl-rerank` when ranking images or videos. Configure common
options (`top_n`, `return_documents`, `instruct`, `fps`) directly on the
descriptor (with `additional_kwargs` for extra provider options) or override them
per call with `model_parameters`. See the
[DashScope Rerank API](https://help.aliyun.com/en/model-studio/text-rerank-api)
for available models and limits.

#### Parameters

| Parameter | Type | Default | Description |
|---|---|---|---|
| `model` | str | Required | Rerank model name (e.g. `qwen3-rerank` or `qwen3-vl-rerank`). |
| `api_key` | str | `$DASHSCOPE_API_KEY` | DashScope API key. |
| `workspace` | str | `None` | Model Studio workspace ID (with `region`, uses `https://{workspace}.{region}.maas.aliyuncs.com`). |
| `region` | str | `"cn-beijing"` | DashScope gateway region. |
| `base_url` | str | `None` | Custom full API root (including path; `/api/v1` is not appended automatically) overriding the default endpoint. |
| `request_timeout` | float | `60.0` | HTTP request timeout in seconds. |
| `top_n` | int | `None` | Maximum number of ranked hits to return. |
| `return_documents` | bool | `None` | Whether hits include the original document. |
| `instruct` | str | `None` | Task instruction guiding rerank relevance. |
| `fps` | float | `None` | Sampling frame rate for video documents. |
| `additional_kwargs` | dict | `{}` | Extra DashScope parameters merged over explicit setup fields and overridden by per-call `model_parameters`. |

#### Run the Example

After [setting up PyFlink]({{< ref "docs/get-started/setup" >}}), run
{{< gh_link file="python/flink_agents/examples/rerank_models/search_ranking_example.py" name="`search_ranking_example.py`" >}}:

```shell
DASHSCOPE_API_KEY=... python -m flink_agents.examples.rerank_models.search_ranking_example
```

The job registers `qwen3-vl-rerank`, ranks two questions against a text, image,
and video candidate, and prints the top hit's index, content block type, and
relevance score.
