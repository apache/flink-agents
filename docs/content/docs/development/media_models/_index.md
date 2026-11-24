---
title: "Media Models"
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

# Media Models

## Overview

Use media models when an Agent needs dedicated media operations rather than
conversational chat responses:

- **Image generation and editing**: produce or transform images from prompts and
  optional reference images.
- **Video generation**: generate video clips from text prompts, conditioning
  media blocks, or both.
- **Transcription**: extract text transcripts from audio or video recordings.
- **Speech synthesis**: synthesize spoken audio from text and voice settings.

If your workflow uses conversation-style multimodal messages instead, see
[Chat with Models]({{< ref "docs/development/calling-llms/chat" >}}). For
available implementations and provider options, see
[Built-in Media Model Connections]({{< ref "docs/development/media_models/providers" >}}).

## Quick start

### Register a connection

Declare a `MEDIA_MODEL_CONNECTION` resource using `@media_model_connection` in
Python, `@MediaModelConnection` in Java, or `media_model_connections` in YAML.
The example below uses DashScope; replace it with any implementation from
[Built-in Media Model Connections]({{< ref "docs/development/media_models/providers" >}}):

{{< tabs "Register a media model connection" >}}
{{< tab "Python" >}}
```python
from flink_agents.api.agents.agent import Agent
from flink_agents.api.decorators import media_model_connection
from flink_agents.api.resource import ResourceDescriptor, ResourceName


class PosterAgent(Agent):
    @media_model_connection
    @staticmethod
    def media() -> ResourceDescriptor:
        return ResourceDescriptor(
            clazz=ResourceName.MediaModel.DASHSCOPE_CONNECTION,
            request_timeout=60.0,
        )
```
{{< /tab >}}
{{< tab "Java" >}}
```java
import org.apache.flink.agents.api.agents.Agent;
import org.apache.flink.agents.api.annotation.MediaModelConnection;
import org.apache.flink.agents.api.resource.PythonResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceName;

public class PosterAgent extends Agent {
    @MediaModelConnection
    public static ResourceDescriptor media() {
        return PythonResourceDescriptor.Builder.newBuilder(
                        ResourceName.MediaModel.Python.DASHSCOPE_CONNECTION)
                .addInitialArgument("request_timeout", 60.0)
                .build();
    }
}
```
{{< /tab >}}
{{< tab "YAML" >}}
```yaml
media_model_connections:
  - name: media
    clazz: dashscope
    type: python
    request_timeout: 60
```
{{< /tab >}}
{{< /tabs >}}

To register a shared connection through the execution environment instead, call
`agents_env.add_resource("media", ResourceType.MEDIA_MODEL_CONNECTION, descriptor)`
in Python (or `agentsEnv.addResource(...)` in Java). See
[Registering Resources]({{< ref "docs/development/create-agent/resources#registering-resources" >}})
and [YAML Resources]({{< ref "docs/development/create-agent/yaml#resources" >}}).

### Declare and use a media model setup

Declare a media model setup with a connection, model name, and default
parameters, then retrieve it by name in an Action:

{{< tabs "Declare a media model setup" >}}
{{< tab "Python" >}}
```python
from flink_agents.api.agents.agent import Agent
from flink_agents.api.decorators import media_model_setup
from flink_agents.api.resource import ResourceDescriptor, ResourceName, ResourceType


class PosterAgent(Agent):
    @media_model_setup
    @staticmethod
    def poster_image() -> ResourceDescriptor:
        return ResourceDescriptor(
            clazz=ResourceName.MediaModel.DASHSCOPE_IMAGE_GENERATION_SETUP,
            connection="media",
            model="qwen-image-3.0",
            size="512*512",
        )
```

Retrieve the setup inside an Action via `ResourceType.MEDIA_MODEL`:

```python
image_model = ctx.get_resource("poster_image", ResourceType.MEDIA_MODEL)
result = image_model.generate(
    "A navy notebook on a white desk.",
    model_parameters={"n": 1},
    timeout=180,
)
generated_image = result.content[0]
```
{{< /tab >}}
{{< tab "Java" >}}
```java
import org.apache.flink.agents.api.agents.Agent;
import org.apache.flink.agents.api.annotation.MediaModelSetup;
import org.apache.flink.agents.api.chat.messages.ImageBlock;
import org.apache.flink.agents.api.media.BaseImageGenerationModelSetup;
import org.apache.flink.agents.api.media.ImageGenerationRequest;
import org.apache.flink.agents.api.media.MediaResult;
import org.apache.flink.agents.api.resource.PythonResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceName;
import org.apache.flink.agents.api.resource.ResourceType;

import java.time.Duration;

public class PosterAgent extends Agent {
    @MediaModelSetup
    public static ResourceDescriptor posterImage() {
        return PythonResourceDescriptor.Builder.newBuilder(
                        ResourceName.MediaModel.Python.DASHSCOPE_IMAGE_GENERATION_SETUP)
                .addInitialArgument("connection", "media")
                .addInitialArgument("model", "qwen-image-3.0")
                .addInitialArgument("size", "512*512")
                .build();
    }
}
```

Retrieve the setup inside an Action via `ResourceType.MEDIA_MODEL`:

```java
BaseImageGenerationModelSetup imageModel =
        (BaseImageGenerationModelSetup) ctx.getResource("posterImage", ResourceType.MEDIA_MODEL);
MediaResult<ImageBlock> result =
        imageModel.generate(
                ImageGenerationRequest.builder("A navy notebook on a white desk.")
                        .modelParameter("n", 1)
                        .build(),
                Duration.ofMinutes(3));
ImageBlock generatedImage = result.getContent().get(0);
```
{{< /tab >}}
{{< tab "YAML" >}}
```yaml
media_model_setups:
  - name: poster_image
    clazz: dashscope_image
    connection: media
    model: qwen-image-3.0
    size: "512*512"
```

Retrieve the setup inside an Action with
`ctx.get_resource("poster_image", ResourceType.MEDIA_MODEL)` (Java:
`ctx.getResource("poster_image", ResourceType.MEDIA_MODEL)`). Omitted `type:`
defaults to `python`; set `type: java` with a Java setup class name in
Java-loaded YAML.
{{< /tab >}}
{{< /tabs >}}

Each call returns a `MediaResult` containing typed output blocks in `content`
(`getContent()`), along with optional `expires_at_ms`, `usage`, `request_id`,
and `metadata`. Calls accept optional `model_parameters` (and `voice` on speech
synthesis) plus `timeout` to override setup defaults for that invocation;
callers must not mutate request parameter containers while a call is in flight.
See [Built-in Media Model Connections]({{< ref "docs/development/media_models/providers" >}})
for provider-specific parameters and defaults.

## Supported Media Models

All task APIs return a `MediaResult` carrying typed media or text blocks. For
creating `ImageBlock`, `AudioBlock`, and `VideoBlock` inputs, see
[Multimodal Messages]({{< ref "docs/development/calling-llms/chat#message-content-blocks" >}}).
Supported sources, YAML aliases, and provider options depend on the connection;
see [Built-in Media Model Connections]({{< ref "docs/development/media_models/providers" >}}).

| Media task | API | Input | Result content |
|---|---|---|---|
| Image generation and editing | `generate(...)` | Prompt and optional reference `ImageBlock`s | `ImageBlock`s |
| Video generation | `generate(...)` | Prompt, ordered media blocks, or both | `VideoBlock`s |
| Transcription | `transcribe(...)` | `AudioBlock` or `VideoBlock` | `TextBlock`s |
| Speech synthesis | `synthesize(...)` | Text and optional `voice` | `AudioBlock`s |

## Asynchronous jobs

Synchronous setups (such as DashScope speech synthesis) complete within a single
request, where per-call `timeout` overrides the connection's HTTP
`request_timeout`. Asynchronous setups backed by cloud jobs (such as image
generation, video generation, and file transcription on DashScope) implement
`AsyncJobModel`. On those setups, calling `generate()` or `transcribe()`
directly submits the job and waits until completion using `timeout` as the
positive polling budget rather than changing HTTP `request_timeout`.

To split submission and completion—so you can track the job handle, inspect
intermediate status, poll with a custom interval, or cancel a pending task—check
for `AsyncJobModel` and call `submit()`, `fetch()`, `wait()`, and `cancel()`:

{{< tabs "Asynchronous media jobs" >}}
{{< tab "Python" >}}
```python
from flink_agents.api.media import AsyncJobModel, MediaJobStatus
from flink_agents.api.resource import ResourceType

image_model = ctx.get_resource("poster_image", ResourceType.MEDIA_MODEL)
if isinstance(image_model, AsyncJobModel):
    job = image_model.submit("A navy notebook on a white desk.")
    state = image_model.fetch(job)
    if state.status in (MediaJobStatus.PENDING, MediaJobStatus.RUNNING):
        # Poll until completion, or call image_model.cancel(job) if no longer needed.
        result = image_model.wait(job, timeout=180, poll_interval=2)
    else:
        result = image_model.wait(job)
    generated_image = result.content[0]
```
{{< /tab >}}
{{< tab "Java" >}}
```java
import org.apache.flink.agents.api.chat.messages.ImageBlock;
import org.apache.flink.agents.api.media.AsyncJobModel;
import org.apache.flink.agents.api.media.BaseImageGenerationModelSetup;
import org.apache.flink.agents.api.media.ImageGenerationRequest;
import org.apache.flink.agents.api.media.MediaJob;
import org.apache.flink.agents.api.media.MediaJobState;
import org.apache.flink.agents.api.media.MediaJobStatus;
import org.apache.flink.agents.api.media.MediaResult;
import org.apache.flink.agents.api.resource.ResourceType;

import java.time.Duration;

BaseImageGenerationModelSetup imageModel =
        (BaseImageGenerationModelSetup) ctx.getResource("posterImage", ResourceType.MEDIA_MODEL);
if (imageModel instanceof AsyncJobModel) {
    @SuppressWarnings("unchecked")
    AsyncJobModel<ImageGenerationRequest, MediaResult<ImageBlock>> asyncModel =
            (AsyncJobModel<ImageGenerationRequest, MediaResult<ImageBlock>>) imageModel;
    MediaJob job =
            asyncModel.submit(ImageGenerationRequest.of("A navy notebook on a white desk."));
    MediaJobState<MediaResult<ImageBlock>> state = asyncModel.fetch(job);
    MediaResult<ImageBlock> result;
    if (state.getStatus() == MediaJobStatus.PENDING
            || state.getStatus() == MediaJobStatus.RUNNING) {
        // Poll until completion, or call asyncModel.cancel(job) if no longer needed.
        result = asyncModel.wait(job, Duration.ofMinutes(3), Duration.ofSeconds(2));
    } else {
        result = asyncModel.wait(job);
    }
    ImageBlock generatedImage = result.getContent().get(0);
}
```
{{< /tab >}}
{{< /tabs >}}

`MediaJob` is JSON-serializable, and callers are responsible for passing each
job handle back to a setup compatible with the connection and capability that
created it. `fetch(job)` returns a `MediaJobState` with `status`, `result`
(when `SUCCEEDED`), and any `error_code` or `error_message`. `wait()` defaults
to a 30-minute polling budget (`1800` s) and a `5`-second `poll_interval`; pass
`timeout=None` (Java: `null`) for unlimited polling, or a strictly positive
duration.

If a job ends in `FAILED`, `CANCELED`, or `UNKNOWN`, `wait()` raises
`MediaJobError` (Java: `MediaJobException`). When the polling budget elapses
first, it raises `MediaJobTimeoutError` (Java: `MediaJobTimeoutException`) with
the `job` handle attached so you can continue waiting or call `cancel(job)`
explicitly. On Java, interrupting a wait stops local polling and throws a
cancellation exception without canceling the remote job.

## Custom connections and setups

{{< hint warning >}}
The custom media model APIs are experimental and unstable, subject to
incompatible changes in future releases.
{{< /hint >}}

### Implement a connection

Extend `BaseMediaModelConnection` to hold shared configuration (credentials,
endpoints, request timeouts) and manage the underlying SDK client lifecycle in
`open()` and `close()`.

### Implement a model setup

Extend the task-specific setup base class matching the operation—`BaseImageGenerationModelSetup`,
`BaseVideoGenerationModelSetup`, `BaseTranscriptionModelSetup`, or
`BaseSpeechSynthesisModelSetup`. Look up the configured connection from
`resourceContext` (`resource_context`) on `open()`, detach cleanly on `close()`
without closing the shared connection, and keep setup instances stateless so
they remain safe across recovery and cross-language calls.

### Support asynchronous jobs (optional)

For capabilities backed by resumable provider jobs, also implement
`AsyncJobModel`. Provide `submit()`, single-observation `fetch()`, and
`cancel()`, reuse the default `wait()` polling loop on `AsyncJobModel`, and
compose the blocking task method (`generate()` or `transcribe()`) over
`submit()` and `wait()`.
