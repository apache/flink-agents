---
title: "Media Providers"
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

## Overview

Media providers expose dedicated, non-chat media capabilities—such as media
generation and audio or speech processing—through a single registered service
connection (`MEDIA_PROVIDER`).

In Flink Agents, media providers follow a three-layer model:

1. **Provider (`BaseMediaProvider`)**: Stores connection and HTTP settings and
   owns the shared client lifecycle.
2. **Model binding**: A capability factory method binds a model name and
   optional default `model_parameters`, returning a reusable capability view.
3. **Invocation**: Call the capability's typed method directly, or use
   [`AsyncJobModel`](#asynchronous-jobs-and-recovery) when the provider backs
   that capability with resumable cloud jobs.

Each provider implements the subset of capabilities its service supports;
calling an unsupported binding method raises `NotImplementedError` in Python or
`UnsupportedOperationException` in Java. Whether a bound capability runs
synchronously or exposes resumable jobs via `AsyncJobModel` is determined by
the provider implementation.

Media providers accept typed inputs directly rather than role-based
conversation turns. [Chat models]({{< ref "docs/development/calling-llms/chat" >}})
instead express requests as messages with roles (including multimodal
messages). Choose the interface that fits your workflow.

See [Built-in Media Providers]({{< ref "docs/development/media_providers/providers" >}})
for available implementations, connection parameters, and provider-specific
requirements.

## Getting started

Register a `MEDIA_PROVIDER` resource with a descriptor for your chosen
implementation. The example below uses the built-in DashScope provider; replace
the class and connection settings with those of any built-in or custom provider
listed in [Built-in Media Providers]({{< ref "docs/development/media_providers/providers" >}}).

{{< tabs "Register a media provider" >}}
{{< tab "Python" >}}
```python
from flink_agents.api.resource import ResourceDescriptor, ResourceName, ResourceType

agents_env.add_resource(
    "media",
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
        "media",
        ResourceType.MEDIA_PROVIDER,
        PythonResourceDescriptor.Builder.newBuilder(ResourceName.MediaProvider.Python.DASHSCOPE)
                .build());
```
{{< /tab >}}
{{< /tabs >}}

You can also declare a provider using `@media_provider` in Python,
`@MediaProvider` in Java, or the YAML `media_providers` section. See
[Registering Resources]({{< ref "docs/development/create-agent/resources#registering-resources" >}})
for registration scopes and
[YAML Resources]({{< ref "docs/development/create-agent/yaml#resources" >}})
for YAML declarations.

## Built-in capabilities

Flink Agents currently defines the capability interfaces below and may expand
this set over time. Inside an Action, retrieve the registered provider with
`ctx.get_resource("media", ResourceType.MEDIA_PROVIDER)` (Java:
`ctx.getResource(...)`) and bind a model for the capability you need.

| Capability | Binding method | Typical inputs | Result `content` |
|---|---|---|---|
| [Image generation and editing](#image-generation-and-editing) | `image(model)` | Text prompt and optional `ImageBlock` references | `ImageBlock`s |
| [Video generation](#video-generation) | `video(model)` | Text prompt, ordered media blocks, or both | `VideoBlock`s |
| [Transcription](#transcription) | `transcription(model)` | `AudioBlock` or `VideoBlock` | `TextBlock`s |
| [Speech synthesis](#speech-synthesis) | `speech(model[, voice])` | Text and optional `voice` | `AudioBlock`s |

See [Multimodal Messages]({{< ref "docs/development/calling-llms/chat#message-content-blocks" >}})
for constructing `ImageBlock`, `AudioBlock`, `VideoBlock`, and `DocumentBlock`
values. The examples below call the DashScope provider registered above; model
names, accepted media sources (URL vs. inline Base64), and `model_parameters`
keys depend on your provider—see
[Built-in Media Providers]({{< ref "docs/development/media_providers/providers" >}}).

### Image generation and editing

Bind an image model with `image(model)` and call `generate()` with a prompt and
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
    ctx.get_resource("media", ResourceType.MEDIA_PROVIDER),
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
        (BaseMediaProvider) ctx.getResource("media", ResourceType.MEDIA_PROVIDER);
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

### Video generation

Bind a video model with `video(model)` and pass a text prompt, ordered media
blocks (`ImageBlock`, `AudioBlock`, `VideoBlock`, or `DocumentBlock`), or both:

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

### Transcription

Bind a transcription model with `transcription(model)` and pass an `AudioBlock`
or `VideoBlock`:

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

### Speech synthesis

Bind a speech model with `speech(model, voice=...)` and call `synthesize()`.
`voice` can be configured as a binding default or overridden per call:

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

## Parameters, timeouts, and results

Configuration is separated into three scopes:

| Scope | Responsibility | Examples |
|---|---|---|
| Provider | Service connection and HTTP settings | Credentials, endpoint/region, `request_timeout` |
| Binding | Model selection and default model parameters | `model`, `model_parameters`, optional `voice` on `speech()` |
| Call | Inputs, per-call overrides, and call timeout | Prompt or media blocks, `model_parameters`, optional `voice`, `timeout` |

Parameter precedence is identical in Python and Java:

```text
call explicit (e.g. voice) > call model_parameters > binding explicit > binding model_parameters > provider default
```

In Java request builders, `.voice(...)` always overrides `"voice"` in
`modelParameters` regardless of builder call order.

- **Per-call `timeout`**: For synchronous capabilities, `timeout` overrides the
  provider's HTTP request timeout for that call. For asynchronous capabilities,
  `timeout` on a direct call (`generate()` or `transcribe()`) sets the polling
  budget (defaulting to 30 minutes) without changing per-request HTTP timeouts.
- **`MediaResult` fields**: Every capability returns a `MediaResult` carrying
  typed blocks in `content` (`getContent()`), along with optional
  `expires_at_ms`, `usage`, `request_id`, and `metadata`.

## Asynchronous jobs and recovery

Capabilities backed by provider-side cloud jobs implement `AsyncJobModel`
(`submit`, `fetch`, `wait`, `cancel`) alongside their task interface. Direct
calls submit a job and poll until completion; check for `AsyncJobModel` when
long-running jobs must survive recovery or when you need manual polling or
cancellation:

{{< tabs "Resume asynchronous media jobs" >}}
{{< tab "Python" >}}
```python
from flink_agents.api.media.media_model import AsyncJobModel

image_model = provider.image("qwen-image-3.0")
if isinstance(image_model, AsyncJobModel):
    job = await ctx.durable_execute_async(
        image_model.submit,
        "A navy notebook on a white desk.",
        model_parameters={"size": "512*512"},
    )
    result = await ctx.durable_execute_async(
        image_model.wait,
        job,
        timeout=180,
        poll_interval=2,
    )
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
  handle returned by `submit()` before waiting (for example, with durable
  execution) so recovery resumes with `fetch(job)` or `wait(job)` instead of
  resubmitting.
- **Polling budget**: `wait()` defaults to 30 minutes (`DEFAULT_WAIT_TIMEOUT`)
  and 5-second intervals (`DEFAULT_POLL_INTERVAL`). Pass `timeout=None` (Java:
  `null`) for unlimited polling. If the budget expires, `MediaJobTimeoutError`
  (Java: `MediaJobTimeoutException`) is raised while the remote task keeps
  running and remains queryable.
- **Cancellation**: `cancel(job)` asks the provider to cancel a submitted job;
  providers that cannot cancel a job in its current status raise
  `MediaJobError` (Java: `MediaJobException`).

## Lifecycle

Flink Agents opens registered providers before use and closes them when their
resource scope ends. Actions should leave lifecycle management to the framework.
Bound capability objects share their provider's connection and lifetime; they
are not registered or closed separately and must not be used after their
provider closes.

For standalone Python use outside a Flink job, construct a provider, call
`open()` before making requests, and call `close()` in a `finally` block when
finished.

## Using cross-language providers

Media providers support Java-to-Python cross-language use: Java Actions can
invoke Python provider implementations through `PythonResourceDescriptor` or
YAML `type: python`. See
[Cross-Language Resources]({{< ref "docs/development/create-agent/resources#cross-language-resources" >}})
for descriptor selection and runtime requirements.

## Custom providers

{{< hint warning >}}
The custom provider APIs are experimental and unstable, subject to incompatible
changes in future releases.
{{< /hint >}}

To integrate another media service, subclass `BaseMediaProvider` (Python:
`flink_agents.api.media.media_provider.BaseMediaProvider`; Java:
`org.apache.flink.agents.api.media.BaseMediaProvider`), manage the shared
client in `open()` and `close()`, and override the capability factory methods
your service supports:

- Return objects implementing the corresponding task interface
  (`ImageGenerationModel`, `VideoGenerationModel`, `TranscriptionModel`,
  `SpeechSynthesisModel`), and additionally implement `AsyncJobModel` only for
  capabilities backed by resumable provider jobs.
- Keep bound capability views lightweight and free of independent mutable state
  so the same `(model, model_parameters)` binding can be recreated safely.
  Shared state belongs on the provider itself (or in a persisted `MediaJob`),
  which also ensures compatibility with Java-to-Python cross-language bridges
  and recovery.

[Built-in Media Providers]({{< ref "docs/development/media_providers/providers" >}}).
