---
title: "Built-in Media Providers"
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

For media provider concepts, capability APIs, parameter precedence, and
asynchronous job recovery, see
[Media Providers]({{< ref "docs/development/media_providers" >}}); for
registration methods and scopes, see
[Resources]({{< ref "docs/development/create-agent/resources#registering-resources" >}}).
This page describes the built-in implementations and their provider options.

### Alibaba Cloud Model Studio (DashScope) {#alibaba-cloud-model-studio-dashscope}

This integration connects to Alibaba Cloud Model Studio (Bailian) through the
DashScope SDK (`DashScopeMediaProvider`).

{{< hint info >}}
The DashScope media provider currently has a Python implementation only. To use
it from Java Agents, see
[Cross-Language Resources]({{< ref "docs/development/create-agent/resources#cross-language-resources" >}}).
{{< /hint >}}

#### Prerequisites

1. Get an API key from [Alibaba Cloud Model Studio](https://dashscope.console.aliyun.com/).
2. Set `DASHSCOPE_API_KEY` in the runtime environment, or pass `api_key` in the
   resource descriptor.

#### DashScopeMediaProvider Parameters

The descriptor configures only the shared service connection. Model names and
generation options belong to capability bindings and calls.

| Parameter | Type | Default | Description |
|---|---|---|---|
| `api_key` | str | `$DASHSCOPE_API_KEY` | DashScope API key, resolved when the provider opens. |
| `workspace` | str | `None` | Model Studio workspace ID. With `region`, selects `https://{workspace}.{region}.maas.aliyuncs.com`. |
| `region` | str | `"cn-beijing"` | DashScope gateway region. |
| `base_url` | str | `None` | Custom API root (including its path) replacing the SDK-derived address. |
| `request_timeout` | float | `60.0` | HTTP timeout in seconds for a single request. |

#### Capability Support and Provider Rules

| Capability | Binding | Execution | Accepted inputs | Key DashScope requirements |
|---|---|---|---|---|
| Image generation and editing | `image(model)` | Async job (`AsyncJobModel`) | Non-empty prompt; optional `ImageBlock` references (URL or Base64) | Output image URLs expire (`expires_at_ms`). |
| Video generation | `video(model)` | Async job (`AsyncJobModel`) | Prompt, ordered media blocks (URL or Base64), or both | Non-empty `media` requires `video_media_roles` in `model_parameters` (one role per block in order, e.g. `["first_frame"]`). Output video URLs expire (`expires_at_ms`). |
| Transcription | `transcription(model)` | Async job (`AsyncJobModel`) | One `AudioBlock` or `VideoBlock` | Accepts **HTTP(S) URLs only** (inline Base64 is rejected). Full DashScope transcription JSON is stored in `metadata["transcription"]`. |
| Speech synthesis | `speech(model[, voice])` | Synchronous | Non-empty text | `voice` is required (at binding or call). Does not implement `AsyncJobModel`. Output audio URL expires (`expires_at_ms`). |

- **Temporary output URLs**: DashScope image, video, and audio URLs expire
  after a retention window. Check `result.expires_at_ms` (Java:
  `result.getExpiresAtMs()`) and save assets you need to keep.
- **Job cancellation**: For asynchronous DashScope capabilities (`image`,
  `video`, `transcription`), `cancel(job)` can cancel only `PENDING` tasks;
  canceling a task in any other status raises `MediaJobError` (Java:
  `MediaJobException`).

#### API References and Available Models

Choose models and voices available to your account in the
[Alibaba Cloud Model Studio model catalog](https://help.aliyun.com/en/model-studio/models).
For model-specific parameters, formats, and limits, see the official API
references:

- [DashScope image generation and editing API](https://help.aliyun.com/en/model-studio/qwen-image-generation-and-editing-api-reference)
- [DashScope video generation API](https://help.aliyun.com/en/model-studio/video-generation-api)
- [DashScope file transcription API](https://help.aliyun.com/en/model-studio/qwen-audio-asr-filetrans-http-api)
- [DashScope speech synthesis API](https://help.aliyun.com/en/model-studio/qwen-tts-api)

#### Run the DashScope Example

After [setting up PyFlink]({{< ref "docs/get-started/setup" >}}), activate your
Python environment, set `DASHSCOPE_API_KEY`, and run
{{< gh_link file="python/flink_agents/examples/media/image_generation_example.py" name="`image_generation_example.py`" >}}:

```shell
DASHSCOPE_API_KEY=... python -m flink_agents.examples.media.image_generation_example
```

The job registers `DashScopeMediaProvider`, submits an async image job to
`qwen-image-3.0` through `durable_execute_async`, waits for completion, and
prints the generated image URLs and request ID.
