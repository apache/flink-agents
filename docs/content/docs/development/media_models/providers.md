---
title: "Built-in Media Model Connections"
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

## Built-in Connections

For general usage, capability APIs, and asynchronous jobs, see
[Media Models]({{< ref "docs/development/media_models" >}}). For
registration methods and scopes, see
[Resources]({{< ref "docs/development/create-agent/resources#registering-resources" >}}).

### Alibaba Cloud Model Studio (DashScope) {#alibaba-cloud-model-studio-dashscope}

Connects to Alibaba Cloud Model Studio (Bailian) through `DashScopeMediaModelConnection`.

{{< hint info >}}
The DashScope media model integration currently has a Python implementation
only. To use it from Java Agents, see
[Cross-Language Resources]({{< ref "docs/development/create-agent/resources#cross-language-resources" >}}).
{{< /hint >}}

#### Prerequisites

1. Get an API key from [Alibaba Cloud Model Studio](https://help.aliyun.com/en/model-studio/get-api-key).
2. Set `DASHSCOPE_API_KEY`, or pass `api_key` in the resource descriptor.

#### Capabilities and Notes

| Capability | Setup class (`media_model_setups` alias) | Execution | Notes |
|---|---|---|---|
| Image generation & editing | `DashScopeImageGenerationModelSetup` (`dashscope_image`) | Async job | Prompt + optional `ImageBlock` references (HTTP(S) URL or Base64). |
| Video generation | `DashScopeVideoGenerationModelSetup` (`dashscope_video`) | Async job | When passing `media` (HTTP(S)/OSS URL or Base64), pass `video_media_roles` on the setup or in `model_parameters` matching block order (e.g. `["first_frame"]`); setup-level `video_media_roles` is ignored for text-only calls. |
| Transcription | `DashScopeTranscriptionModelSetup` (`dashscope_asr`) | Async job | Accepts **HTTP(S) URLs only** (`AudioBlock` or `VideoBlock`). Raw response JSON is kept in `metadata["transcription"]`. |
| Speech synthesis | `DashScopeSpeechSynthesisModelSetup` (`dashscope_tts`) | Synchronous | Requires `voice` (on the setup or per call). Does not implement `AsyncJobModel`. |

Generated media URLs expire after a retention window (check `expires_at_ms` and
save assets you need to keep). For async capabilities, `cancel(job)` applies to
pending tasks.

#### Connection Parameters

| Parameter | Type | Default | Description |
|---|---|---|---|
| `api_key` | str | `$DASHSCOPE_API_KEY` | DashScope API key. |
| `workspace` | str | `None` | Model Studio workspace ID (with `region`, uses `https://{workspace}.{region}.maas.aliyuncs.com`). |
| `region` | str | `"cn-beijing"` | DashScope gateway region. |
| `base_url` | str | `None` | Custom full API root (including path; `/api/v1` is not appended automatically) overriding the default endpoint. |
| `request_timeout` | float | `60.0` | HTTP request timeout in seconds. |

#### Setup Parameters

Every `DashScope*ModelSetup` (`DashScopeSpeechSynthesisModelSetup`,
`DashScopeTranscriptionModelSetup`, `DashScopeImageGenerationModelSetup`, and
`DashScopeVideoGenerationModelSetup`) accepts:

| Parameter | Type | Required | Description |
|---|---|---|---|
| `connection` | str | Yes | Name of the `DashScopeMediaModelConnection` resource supplying the shared DashScope client. |
| `model` | str | Yes | DashScope model name to invoke (for example `qwen-image-3.0`, `wan2.7-i2v`, `fun-asr`, `cosyvoice-v3-plus`). |
| `additional_kwargs` | dict | No | Additional DashScope setup parameters merged over explicit setup fields and overridden by per-call `model_parameters`. Defaults to `{}`. |

Capability-specific setup parameters:

- `DashScopeSpeechSynthesisModelSetup`: `voice`, `format`, `sample_rate`, `language_type`.
- `DashScopeTranscriptionModelSetup`: `language_hints`, `diarization_enabled`, `speaker_count`, `channel_id`.
- `DashScopeImageGenerationModelSetup`: `size`, `n`, `negative_prompt`, `prompt_extend`, `watermark`, `seed`.
- `DashScopeVideoGenerationModelSetup`: `size`, `resolution`, `ratio`, `duration`, `prompt_extend`, `watermark`, `seed`, `video_media_roles`.

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

The job registers `DashScopeMediaModelConnection` and a
`DashScopeImageGenerationModelSetup` (`qwen-image-3.0`), submits an async image
job via `durable_execute_async`, waits for completion, and prints the generated
image URLs and request ID.
