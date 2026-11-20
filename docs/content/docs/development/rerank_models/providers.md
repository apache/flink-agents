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

For rerank model concepts, result fields, and parameter precedence, see
[Rerank Models]({{< ref "docs/development/rerank_models" >}}); for registration
methods and scopes, see
[Resources]({{< ref "docs/development/create-agent/resources#registering-resources" >}}).
This page describes the built-in implementations and their provider options.

### Alibaba Cloud Model Studio (DashScope) {#alibaba-cloud-model-studio-dashscope}

This integration connects to Alibaba Cloud Model Studio (Bailian) through the
DashScope SDK (`DashScopeRerankModel`) for text and multimodal reranking.

{{< hint info >}}
The DashScope rerank model currently has a Python implementation only. To use
it from Java Agents, see
[Cross-Language Resources]({{< ref "docs/development/create-agent/resources#cross-language-resources" >}}).
{{< /hint >}}

#### Prerequisites

1. Get an API key from [Alibaba Cloud Model Studio](https://dashscope.console.aliyun.com/).
2. Set `DASHSCOPE_API_KEY` in the runtime environment, or pass `api_key` in the
   resource descriptor.

#### DashScopeRerankModel Parameters

| Parameter | Type | Default | Description |
|---|---|---|---|
| `model` | str | Required | Rerank model name (for example, `qwen3-rerank` or `qwen3-vl-rerank`). |
| `api_key` | str | `$DASHSCOPE_API_KEY` | DashScope API key, resolved when the resource opens. |
| `workspace` | str | `None` | Model Studio workspace ID. With `region`, selects `https://{workspace}.{region}.maas.aliyuncs.com`. |
| `region` | str | `"cn-beijing"` | DashScope gateway region. |
| `base_url` | str | `None` | Custom API root (including its path) replacing the SDK-derived address. |
| `request_timeout` | float | `60.0` | HTTP timeout in seconds for a single request. |
| `model_parameters` | dict | `{}` | Default DashScope model parameters (such as `top_n`, `return_documents`, `instruct`, or `fps`). |

#### Supported Inputs

| Input | Supported blocks | Supported sources |
|---|---|---|
| Query | `TextBlock`, `ImageBlock` | Non-empty text; image HTTP(S) URL or inline Base64 |
| Document | `TextBlock`, `ImageBlock`, `VideoBlock` | Non-empty text; image HTTP(S) URL or inline Base64; video **HTTP(S) URL only** |

- Use a text model such as `qwen3-rerank` for text-only ranking, or a
  multimodal model such as `qwen3-vl-rerank` when any query or document is an
  image or video.
- Pass model-specific options such as `instruct` or `fps` via
  `model_parameters`.
- See the [DashScope Rerank API](https://help.aliyun.com/en/model-studio/text-rerank-api)
  for available models, limits, and parameters.

#### Run the DashScope Example

After [setting up PyFlink]({{< ref "docs/get-started/setup" >}}), activate your
Python environment, set `DASHSCOPE_API_KEY`, and run
{{< gh_link file="python/flink_agents/examples/rerank_models/search_ranking_example.py" name="`search_ranking_example.py`" >}}:

```shell
DASHSCOPE_API_KEY=... python -m flink_agents.examples.rerank_models.search_ranking_example
```

The job registers `qwen3-vl-rerank`, ranks two questions against a text, image,
and video candidate, and prints the top hit's index, content block type, and
relevance score.
