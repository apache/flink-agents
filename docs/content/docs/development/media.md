---
title: Media Providers
weight: 9
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

# Media Providers

Media providers expose dedicated operations for generating images and videos,
transcribing recordings, and synthesizing speech. Register one provider for your
service connection, bind a model for the capability you need, and reuse the
bound model across requests.

| Operation | Binding method | Inputs | Result `content` | DashScope execution |
|---|---|---|---|---|
| [Image generation and editing](#image-generation-and-editing) | `image(model)` | Text prompt and optional `ImageBlock` references | `ImageBlock` URLs | Async job (`AsyncJobModel`) |
| [Video generation](#video-generation) | `video(model)` | Text prompt, ordered media blocks, or both | `VideoBlock` URL | Async job (`AsyncJobModel`) |
| [Transcription](#transcription) | `transcription(model)` | `AudioBlock` or `VideoBlock` | `TextBlock`s | Async job (`AsyncJobModel`) |
| [Speech synthesis](#speech-synthesis) | `speech(model[, voice])` | Non-empty text and a `voice` | `AudioBlock` URL | Synchronous |

Media providers take typed inputs directly and support resumable provider jobs
where backed by asynchronous APIs. [Chat models]({{< ref "docs/development/calling-llms/chat" >}})
instead express requests as role-based messages. Choose the interface that fits
your workflow.

## Register a provider

Alibaba Cloud Model Studio (DashScope) is built in as a Python implementation,
`DashScopeMediaProvider`. Java Actions can use it through
[Cross-Language Resources]({{< ref "docs/development/create-agent/resources#cross-language-resources" >}}).
Set `DASHSCOPE_API_KEY` in the runtime environment, then register a provider
descriptor:

{{< tabs "Register a media provider" >}}
{{< tab "Python" >}}
```python
from flink_agents.api.resource import ResourceDescriptor, ResourceName, ResourceType

agents_env.add_resource(
    "dashscope",
    ResourceType.MEDIA_PROVIDER,
    ResourceDescriptor(clazz=ResourceName.MediaProvider.DASHSCOPE),
)
```
{{< /tab >}}
{{< tab "Java" >}}
```java
import org.apache.flink.agents.api.resource.PythonResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceName;
import org.apache.flink.agents.api.resource.ResourceType;

agentsEnv.addResource(
        "dashscope",
        ResourceType.MEDIA_PROVIDER,
        PythonResourceDescriptor.Builder.newBuilder(ResourceName.MediaProvider.Python.DASHSCOPE)
                .build());
```
{{< /tab >}}
{{< /tabs >}}

You can also declare the descriptor using `@media_provider` in Python,
`@MediaProvider` in Java, or the YAML `media_providers` section. See
[Registering Resources]({{< ref "docs/development/create-agent/resources#registering-resources" >}})
for registration scopes and
[YAML Resources]({{< ref "docs/development/create-agent/yaml#resources" >}})
for YAML declarations.

### DashScope Provider Parameters

The provider descriptor configures only the service connection. Model names and
generation parameters belong to the capability calls below.

| Parameter | Type | Default | Description |
|---|---|---|---|
| `api_key` | str | `$DASHSCOPE_API_KEY` | DashScope API key, resolved when the provider opens. |
| `workspace` | str | `None` | Model Studio workspace ID. With `region`, selects `https://{workspace}.{region}.maas.aliyuncs.com`. |
| `region` | str | `"cn-beijing"` | DashScope gateway region. |
| `base_url` | str | `None` | Custom API root (including its path) replacing the SDK-derived address. |
| `request_timeout` | float | `60.0` | HTTP timeout in seconds for a single request. |

## Capabilities

Inside an Action, retrieve the provider by its registered name and
`ResourceType.MEDIA_PROVIDER`. Choose models and voices available to your
account in the [Alibaba Cloud Model Studio model catalog](https://help.aliyun.com/en/model-studio/models).
See [Multimodal Messages]({{< ref "docs/development/calling-llms/chat#message-content-blocks" >}})
for constructing `ImageBlock`, `AudioBlock`, and `VideoBlock` values.

### Image generation and editing

Bind a model with `image(model)` and call `generate()` with a prompt and
optional reference images:

{{< tabs "Image generation and editing" >}}
{{< tab "Python" >}}
```python
from typing import cast

from flink_agents.api.chat_message import ImageBlock
from flink_agents.api.media.media_provider import BaseMediaProvider
from flink_agents.api.resource import ResourceType

provider = cast(
    BaseMediaProvider,
    ctx.get_resource("dashscope", ResourceType.MEDIA_PROVIDER),
)
image_model = provider.image("qwen-image-3.0", model_parameters={"n": 1})
generated = image_model.generate(
    "A navy notebook on a white desk.",
    model_parameters={"size": "512*512"},
    timeout=180,
)

# Edit with reference images
reference = ImageBlock.from_url("image/png", "https://example.com/notebook.png")
edited = image_model.generate(
    "Change the notebook cover to forest green.",
    images=[reference],
    model_parameters={"n": 2},
)
```
{{< /tab >}}
{{< tab "Java" >}}
```java
import org.apache.flink.agents.api.chat.messages.ImageBlock;
import org.apache.flink.agents.api.media.BaseMediaProvider;
import org.apache.flink.agents.api.media.ImageGenerationModel;
import org.apache.flink.agents.api.media.ImageGenerationRequest;
import org.apache.flink.agents.api.media.MediaResult;
import org.apache.flink.agents.api.resource.ResourceType;

import java.time.Duration;
import java.util.List;
import java.util.Map;

BaseMediaProvider provider =
        (BaseMediaProvider) ctx.getResource("dashscope", ResourceType.MEDIA_PROVIDER);
ImageGenerationModel imageModel = provider.image("qwen-image-3.0", Map.of("n", 1));
MediaResult<ImageBlock> generated =
        imageModel.generate(
                ImageGenerationRequest.builder("A navy notebook on a white desk.")
                        .modelParameter("size", "512*512")
                        .build(),
                Duration.ofMinutes(3));

// Edit with reference images
ImageBlock reference = ImageBlock.fromUrl("image/png", "https://example.com/notebook.png");
MediaResult<ImageBlock> edited =
        imageModel.generate(
                ImageGenerationRequest.builder("Change the notebook cover to forest green.")
                        .images(List.of(reference))
                        .modelParameter("n", 2)
                        .build());
```
{{< /tab >}}
{{< /tabs >}}

Reference images accept HTTP(S) URLs or inline Base64 data (`ImageBlock.from_bytes`
/ `ImageBlock.fromBytes`). Generated images are returned as `ImageBlock` URLs in
`result.content` (Java: `result.getContent()`). DashScope image URLs expire
(check `expires_at_ms` / `getExpiresAtMs()`), so save any images you need to
keep. See the [DashScope image generation and editing API](https://help.aliyun.com/en/model-studio/qwen-image-generation-and-editing-api-reference)
for model-specific parameters and limits.

### Video generation

Bind a model with `video(model)` and pass a text prompt, ordered media inputs,
or both:

{{< tabs "Video generation" >}}
{{< tab "Python" >}}
```python
from flink_agents.api.chat_message import ImageBlock

video_model = provider.video("wan3.0-video")
first_frame = ImageBlock.from_url("image/png", "https://example.com/frame.png")
result = video_model.generate(
    "Slow camera push toward the notebook.",
    media=[first_frame],
    model_parameters={"video_media_roles": ["first_frame"], "duration": 5},
    timeout=600,
)
```
{{< /tab >}}
{{< tab "Java" >}}
```java
import org.apache.flink.agents.api.chat.messages.ImageBlock;
import org.apache.flink.agents.api.chat.messages.VideoBlock;
import org.apache.flink.agents.api.media.MediaResult;
import org.apache.flink.agents.api.media.VideoGenerationModel;
import org.apache.flink.agents.api.media.VideoGenerationRequest;

import java.time.Duration;
import java.util.List;

VideoGenerationModel videoModel = provider.video("wan3.0-video");
ImageBlock firstFrame = ImageBlock.fromUrl("image/png", "https://example.com/frame.png");
MediaResult<VideoBlock> result =
        videoModel.generate(
                VideoGenerationRequest.builder("Slow camera push toward the notebook.")
                        .media(List.of(firstFrame))
                        .modelParameter("video_media_roles", List.of("first_frame"))
                        .modelParameter("duration", 5)
                        .build(),
                Duration.ofMinutes(10));
```
{{< /tab >}}
{{< /tabs >}}

`media` accepts `ImageBlock`, `AudioBlock`, `VideoBlock`, and `DocumentBlock`
values as URLs or inline Base64 data. When `media` is non-empty, DashScope
requires `video_media_roles` in `model_parameters` with one role name per media
block in matching order (for example, `["first_frame"]` or
`["first_frame", "last_frame"]`). The result carries a temporary `VideoBlock`
URL (`expires_at_ms`). See the [DashScope video generation API](https://help.aliyun.com/en/model-studio/video-generation-api)
for supported models, roles, and parameters.

### Transcription

Bind a file-transcription model with `transcription(model)` and pass an
`AudioBlock` or `VideoBlock`:

{{< tabs "Transcription" >}}
{{< tab "Python" >}}
```python
from flink_agents.api.chat_message import AudioBlock

transcription_model = provider.transcription("fun-asr")
recording = AudioBlock.from_url("audio/mpeg", "https://example.com/meeting.mp3")
result = transcription_model.transcribe(recording, timeout=300)
```
{{< /tab >}}
{{< tab "Java" >}}
```java
import org.apache.flink.agents.api.chat.messages.AudioBlock;
import org.apache.flink.agents.api.chat.messages.TextBlock;
import org.apache.flink.agents.api.media.MediaResult;
import org.apache.flink.agents.api.media.TranscriptionModel;
import org.apache.flink.agents.api.media.TranscriptionRequest;

import java.time.Duration;

TranscriptionModel transcriptionModel = provider.transcription("fun-asr");
AudioBlock recording = AudioBlock.fromUrl("audio/mpeg", "https://example.com/meeting.mp3");
MediaResult<TextBlock> result =
        transcriptionModel.transcribe(TranscriptionRequest.of(recording), Duration.ofMinutes(5));
```
{{< /tab >}}
{{< /tabs >}}

DashScope file transcription accepts **only accessible HTTP(S) URLs**; inline
Base64 audio or video blocks are rejected. Transcript segments are returned as
`TextBlock` items in `result.content`, and the full DashScope transcription
JSON payload is retained in `result.metadata["transcription"]` (Java:
`result.getMetadata().get("transcription")`). See the
[DashScope file transcription API](https://help.aliyun.com/en/model-studio/qwen-audio-asr-filetrans-http-api)
for supported formats and options.

### Speech synthesis

Bind a speech model with `speech(model, voice=...)` and call `synthesize()`:

{{< tabs "Speech synthesis" >}}
{{< tab "Python" >}}
```python
speech_model = provider.speech("qwen-audio-3.0-tts-flash", voice="longanhuan_v3.6")
result = speech_model.synthesize("Your notebook is ready.")
```
{{< /tab >}}
{{< tab "Java" >}}
```java
import org.apache.flink.agents.api.chat.messages.AudioBlock;
import org.apache.flink.agents.api.media.MediaResult;
import org.apache.flink.agents.api.media.SpeechSynthesisModel;

SpeechSynthesisModel speechModel =
        provider.speech("qwen-audio-3.0-tts-flash", "longanhuan_v3.6");
MediaResult<AudioBlock> result = speechModel.synthesize("Your notebook is ready.");
```
{{< /tab >}}
{{< /tabs >}}

DashScope speech synthesis requires a `voice` (set at binding or overridden per
call) and runs synchronously—it does not implement `AsyncJobModel`. The result
contains an `AudioBlock` URL (`expires_at_ms`). See the
[DashScope speech synthesis API](https://help.aliyun.com/en/model-studio/qwen-tts-api)
for available voices and parameters such as `format`, `sample_rate`, and
`language_type`.

## Parameters, timeouts, and results

Configuration is separated into three scopes:

| Scope | Responsibility | Examples |
|---|---|---|
| Provider | Service connection and HTTP settings | `api_key`, `workspace`, `region`, `base_url`, `request_timeout` |
| Binding | Model selection and default model parameters | `model`, `model_parameters`, optional `voice` on `speech()` |
| Call | Inputs, per-call overrides, and call timeout | prompt or media blocks, `model_parameters`, optional `voice`, `timeout` |

Parameter precedence is identical in Python and Java:

```text
call explicit (e.g. voice) > call model_parameters > binding explicit > binding model_parameters > provider default
```

In Java request builders, `.voice(...)` always takes precedence over `"voice"`
in `modelParameters` regardless of builder call order.

- **Per-call `timeout`**: For synchronous capabilities (DashScope `speech()`),
  `timeout` overrides the provider's HTTP `request_timeout` for that request.
  For asynchronous capabilities (`image()`, `video()`, `transcription()`),
  `timeout` on a direct call (`generate()` or `transcribe()`) sets the polling
  budget (defaulting to 30 minutes) without changing the per-request HTTP
  `request_timeout`.
- **`MediaResult` fields**: Every capability returns a `MediaResult` containing
  typed blocks in `content` (`getContent()`), along with optional
  `expires_at_ms`, `usage`, `request_id`, and `metadata`.

## Asynchronous jobs and recovery

Capabilities backed by cloud jobs implement `AsyncJobModel` (`submit`, `fetch`,
`wait`, `cancel`) alongside their task interface. In DashScope, `image()`,
`video()`, and `transcription()` implement `AsyncJobModel`, while `speech()` is
synchronous.

Direct calls (`generate()`, `transcribe()`) submit a job and poll until it
finishes. Use `AsyncJobModel` directly when long-running jobs must survive
recovery or when you need manual polling or cancellation:

{{< tabs "Resume asynchronous media jobs" >}}
{{< tab "Python" >}}
```python
from flink_agents.api.media.media_model import AsyncJobModel

image_model = provider.image("qwen-image-3.0")
if isinstance(image_model, AsyncJobModel):
    job = image_model.submit(
        "A navy notebook on a white desk.",
        model_parameters={"size": "512*512"},
    )
    result = image_model.wait(job, timeout=180, poll_interval=2)
```
{{< /tab >}}
{{< tab "Java" >}}
```java
import org.apache.flink.agents.api.chat.messages.ImageBlock;
import org.apache.flink.agents.api.media.AsyncJobModel;
import org.apache.flink.agents.api.media.ImageGenerationModel;
import org.apache.flink.agents.api.media.ImageGenerationRequest;
import org.apache.flink.agents.api.media.MediaJob;
import org.apache.flink.agents.api.media.MediaResult;

import java.time.Duration;

ImageGenerationModel imageModel = provider.image("qwen-image-3.0");
if (imageModel instanceof AsyncJobModel) {
    @SuppressWarnings("unchecked")
    AsyncJobModel<ImageGenerationRequest, MediaResult<ImageBlock>> asyncModel =
            (AsyncJobModel<ImageGenerationRequest, MediaResult<ImageBlock>>) imageModel;
    MediaJob job =
            asyncModel.submit(
                    ImageGenerationRequest.builder("A navy notebook on a white desk.")
                            .modelParameter("size", "512*512")
                            .build());
    MediaResult<ImageBlock> result =
            asyncModel.wait(job, Duration.ofMinutes(3), Duration.ofSeconds(2));
}
```
{{< /tab >}}
{{< /tabs >}}

- **Recovery**: `MediaJob` is immutable and JSON-serializable. Persist the job
  handle returned by `submit()` before waiting so recovery can resume with
  `fetch(job)` or `wait(job)` instead of resubmitting.
- **Polling budget**: `wait()` defaults to 30 minutes (`DEFAULT_WAIT_TIMEOUT`)
  and 5-second intervals (`DEFAULT_POLL_INTERVAL`). Pass `timeout=None` (Java:
  `null`) for unlimited polling. If the budget expires, `MediaJobTimeoutError`
  (Java: `MediaJobTimeoutException`) is raised while the remote task keeps
  running and remains queryable.
- **Cancellation**: In DashScope, `cancel(job)` cancels only `PENDING` tasks;
  canceling any other status raises `MediaJobError` (Java: `MediaJobException`).

## Run the example

After [setting up PyFlink]({{< ref "docs/get-started/setup" >}}), activate your
Python environment, set `DASHSCOPE_API_KEY`, and run
{{< gh_link file="python/flink_agents/examples/media/image_generation_example.py" name="`image_generation_example.py`" >}}:

```shell
DASHSCOPE_API_KEY=... python -m flink_agents.examples.media.image_generation_example
```

The job registers `DashScopeMediaProvider`, submits an async image job to
`qwen-image-3.0` through `durable_execute_async`, waits for completion, and
prints the generated image URLs and request ID.

## Provider lifecycle

Flink Agents opens registered providers before use and closes them when their
resource scope ends. Actions should leave lifecycle management to the framework.
Capability objects share their provider's connection and lifetime; they are not
registered or closed separately and must not be used after their provider closes.

For standalone Python use, construct `DashScopeMediaProvider`, call `open()`
before making requests, and call `close()` in a `finally` block when finished.
