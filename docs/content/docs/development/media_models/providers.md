---
title: "Built-in Media Model Providers"
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

For general usage, capability APIs, and asynchronous jobs, see
[Media Models]({{< ref "docs/development/media_models" >}}). For
registration methods and scopes, see
[Resources]({{< ref "docs/development/create-agent/resources#registering-resources" >}}).

### Alibaba Cloud Model Studio (DashScope) {#alibaba-cloud-model-studio-dashscope}

Connects to Alibaba Cloud Model Studio (Bailian) through `DashScopeMediaModelProvider`.

{{< hint info >}}
The DashScope media model provider currently has a Python implementation only.
To use it from Java Agents, see
[Cross-Language Resources]({{< ref "docs/development/create-agent/resources#cross-language-resources" >}}).
{{< /hint >}}

#### Prerequisites

1. Get an API key from [Alibaba Cloud Model Studio](https://help.aliyun.com/en/model-studio/get-api-key).
2. Set `DASHSCOPE_API_KEY`, or pass `api_key` in the resource descriptor.

#### Capabilities and Notes

| Capability | Binding | Execution | Notes |
|---|---|---|---|
| Image generation & editing | `image(model)` | Async job | Prompt + optional `ImageBlock` references (URL or Base64). |
| Video generation | `video(model)` | Async job | When passing `media` (URL or Base64), also pass `video_media_roles` in `model_parameters` matching block order (e.g. `["first_frame"]`). |
| Transcription | `transcription(model)` | Async job | Accepts **HTTP(S) URLs only** (`AudioBlock` or `VideoBlock`). Raw response JSON is kept in `metadata["transcription"]`. |
| Speech synthesis | `speech(model[, voice])` | Synchronous | Requires `voice` (at binding or call). Does not implement `AsyncJobModel`. |

Generated media URLs expire after a retention window (check `expires_at_ms` and
save assets you need to keep). For async capabilities, `cancel(job)` applies to
pending tasks.

#### Connection Parameters

| Parameter | Type | Default | Description |
|---|---|---|---|
| `api_key` | str | `$DASHSCOPE_API_KEY` | DashScope API key. |
| `workspace` | str | `None` | Model Studio workspace ID (with `region`, uses `https://{workspace}.{region}.maas.aliyuncs.com`). |
| `region` | str | `"cn-beijing"` | DashScope gateway region. |
| `base_url` | str | `None` | Custom API root overriding the default endpoint. |
| `request_timeout` | float | `60.0` | HTTP request timeout in seconds. |

#### Models and API References

Pick models and voices available to your account from the
[Alibaba Cloud Model Studio model catalog](https://help.aliyun.com/en/model-studio/models),
and refer to the official API docs for model-specific parameters and limits:

- [Image generation and editing](https://help.aliyun.com/en/model-studio/qwen-image-generation-and-editing-api-reference)
- [Video generation](https://help.aliyun.com/en/model-studio/video-generation-api)
- [File transcription](https://help.aliyun.com/en/model-studio/qwen-audio-asr-filetrans-http-api)
- [Speech synthesis](https://help.aliyun.com/en/model-studio/qwen-tts-api)

#### Run the Example

After [setting up PyFlink]({{< ref "docs/get-started/setup" >}}), run
{{< gh_link file="python/flink_agents/examples/media/image_generation_example.py" name="`image_generation_example.py`" >}}:

```shell
DASHSCOPE_API_KEY=... python -m flink_agents.examples.media.image_generation_example
```

The job registers `DashScopeMediaModelProvider`, submits an async image job to
`qwen-image-3.0` via `durable_execute_async`, waits for completion, and prints
the generated image URLs and request ID.
