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

Use media models when an Agent needs dedicated media operations—such as
generating images or videos, transcribing recordings, or synthesizing speech.
Register one `MEDIA_MODEL_PROVIDER` resource for your service connection, bind
a model for the capability you need, and reuse it across requests.

If your workflow uses conversation-style multimodal messages instead, see
[Chat with Models]({{< ref "docs/development/calling-llms/chat" >}}). For
available implementations and provider options, see
[Built-in Media Model Providers]({{< ref "docs/development/media_models/providers" >}}).

## Quick start

### 1. Register a provider

Register a `MEDIA_MODEL_PROVIDER` resource with your chosen provider
descriptor. The example below uses DashScope; replace it with any provider from
[Built-in Media Model Providers]({{< ref "docs/development/media_models/providers" >}}):

{{< tabs "Register a media model provider" >}}
{{< tab "Python" >}}
```python
from flink_agents.api.resource import ResourceDescriptor, ResourceName, ResourceType

agents_env.add_resource(
    "media",
    ResourceType.MEDIA_MODEL_PROVIDER,
    ResourceDescriptor(clazz=ResourceName.MediaModelProvider.DASHSCOPE),
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
        ResourceType.MEDIA_MODEL_PROVIDER,
        PythonResourceDescriptor.Builder.newBuilder(
                        ResourceName.MediaModelProvider.Python.DASHSCOPE)
                .build());
```
{{< /tab >}}
{{< /tabs >}}

You can also declare a provider using `@media_model_provider` in Python,
`@MediaModelProvider` in Java, or the YAML `media_model_providers` section. See
[Registering Resources]({{< ref "docs/development/create-agent/resources#registering-resources" >}})
and [YAML Resources]({{< ref "docs/development/create-agent/yaml#resources" >}}).

### 2. Bind a model and call a capability

Inside an Action, retrieve the provider, select a model, and call the
capability:

{{< tabs "Call media capabilities" >}}
{{< tab "Python" >}}
```python
from typing import cast

from flink_agents.api.media.media_model_provider import BaseMediaModelProvider
from flink_agents.api.resource import ResourceType

provider = cast(
    BaseMediaModelProvider,
    ctx.get_resource("media", ResourceType.MEDIA_MODEL_PROVIDER),
)
image_model = provider.image("qwen-image-3.0")
result = image_model.generate(
    "A navy notebook on a white desk.",
    model_parameters={"size": "512*512"},
    timeout=180,
)
generated_image = result.content[0]
```
{{< /tab >}}
{{< tab "Java" >}}
```java
import org.apache.flink.agents.api.chat.messages.ImageBlock;
import org.apache.flink.agents.api.media.BaseMediaModelProvider;
import org.apache.flink.agents.api.media.ImageGenerationModel;
import org.apache.flink.agents.api.media.ImageGenerationRequest;
import org.apache.flink.agents.api.media.MediaResult;
import org.apache.flink.agents.api.resource.ResourceType;

import java.time.Duration;

BaseMediaModelProvider provider =
        (BaseMediaModelProvider) ctx.getResource("media", ResourceType.MEDIA_MODEL_PROVIDER);
ImageGenerationModel imageModel = provider.image("qwen-image-3.0");
MediaResult<ImageBlock> result =
        imageModel.generate(
                ImageGenerationRequest.builder("A navy notebook on a white desk.")
                        .modelParameter("size", "512*512")
                        .build(),
                Duration.ofMinutes(3));
ImageBlock generatedImage = result.getContent().get(0);
```
{{< /tab >}}
{{< /tabs >}}

Each call returns a `MediaResult` containing typed output blocks in `content`
(`getContent()`), along with optional `expires_at_ms`, `usage`, `request_id`,
and `metadata`.

## Capabilities

Flink Agents currently includes the capabilities below and may expand this set
over time. Supported capabilities, media sources (URL or Base64), and
model-specific options depend on the provider; see
[Built-in Media Model Providers]({{< ref "docs/development/media_models/providers" >}}).

| Capability | Binding & call | Inputs | Result `content` |
|---|---|---|---|
| Image generation and editing | `image(model).generate(...)` | Prompt and optional reference `ImageBlock`s | `ImageBlock`s |
| Video generation | `video(model).generate(...)` | Prompt, ordered media blocks, or both | `VideoBlock`s |
| Transcription | `transcription(model).transcribe(...)` | `AudioBlock` or `VideoBlock` | `TextBlock`s |
| Speech synthesis | `speech(model[, voice]).synthesize(...)` | Text and optional `voice` | `AudioBlock`s |

See [Multimodal Messages]({{< ref "docs/development/calling-llms/chat#message-content-blocks" >}})
for creating `ImageBlock`, `AudioBlock`, `VideoBlock`, and `DocumentBlock`
inputs.

### Synchronous and asynchronous execution

Depending on the provider and capability, a bound model executes either
synchronously or through an asynchronous cloud job (see
[Built-in Media Model Providers]({{< ref "docs/development/media_models/providers" >}})
for each provider's execution mode):

- **Synchronous capabilities** (such as speech synthesis on DashScope) complete
  within a single request and return `MediaResult` directly.
- **Asynchronous capabilities** (such as image generation, video generation,
  and file transcription on DashScope) run as provider-side jobs and implement
  `AsyncJobModel`. Calling `generate()` or `transcribe()` directly submits the
  job and polls until completion; for recovery-safe execution, manual polling,
  or cancellation, use `AsyncJobModel` directly as shown in
  [Asynchronous jobs and recovery](#asynchronous-jobs-and-recovery).

### Parameters

Parameters are configured at three levels, with narrower levels overriding
broader defaults:

- **Provider level (`MEDIA_MODEL_PROVIDER` descriptor)**: Connection and client
  settings shared across capabilities—for example `api_key`, `region`,
  `base_url`, and `request_timeout`.
- **Model binding level (`image()`, `video()`, `transcription()`, `speech()`)**:
  Defaults reused across calls to that model—for example
  `model_parameters={"size": "512*512"}` on `image()`, or a default `voice` on
  `speech()`.
- **Per-call level (`generate()`, `transcribe()`, `synthesize()`)**:
  Request-specific overrides—for example `model_parameters` (such as `size` or
  `n`), `voice`, and `timeout` (`timeout=30` in Python or
  `Duration.ofSeconds(30)` in Java).

Per-call `timeout` overrides the provider's `request_timeout` for synchronous
calls, or sets the polling budget (such as `timeout=180` or
`Duration.ofMinutes(3)`) for asynchronous calls.

## Asynchronous jobs and recovery

For asynchronous capabilities that implement `AsyncJobModel`, you can split
submission and completion with `submit()`, `fetch()`, `wait()`, and `cancel()`
so a long-running job survives recovery or can be polled and canceled directly:

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

`MediaJob` is serializable, so persisting the handle from `submit()` lets
recovery resume with `fetch(job)` or `wait(job)`. `fetch()` returns a
`MediaJobState` carrying `status`, `result` (when `SUCCEEDED`), and any
`error_code` or `error_message`. `wait()` defaults to a 30-minute polling
budget (`1800` s) and a `5`-second `poll_interval`; pass `timeout=None` (Java:
`null`) for unlimited polling.

If a job ends as `FAILED`, `CANCELED`, or `UNKNOWN`, `wait()` raises
`MediaJobError` (Java: `MediaJobException`). If the polling budget elapses
first, it raises `MediaJobTimeoutError` (Java: `MediaJobTimeoutException`)—the
provider-side job is not canceled, so you can resume waiting or cancel it with
the attached `job` handle.

## Lifecycle and cross-language use

- **Lifecycle**: Flink Agents opens and closes registered providers
  automatically. Bound capabilities share their provider's lifetime and are not
  closed separately. For standalone Python scripts, call `open()` before use
  and `close()` in a `finally` block.
- **Cross-language**: Java Agents can use Python media model providers via
  `PythonResourceDescriptor` or YAML `type: python`. See
  [Cross-Language Resources]({{< ref "docs/development/create-agent/resources#cross-language-resources" >}}).

## Custom providers

{{< hint warning >}}
The custom provider APIs are experimental and unstable, subject to incompatible
changes in future releases.
{{< /hint >}}

To integrate another media service, subclass `BaseMediaModelProvider`, manage
the client in `open()` and `close()`, and implement the capability factories
your service supports (additionally implementing `AsyncJobModel` for
capabilities backed by resumable jobs). Keep bound capability views stateless
so they can be safely recreated from configuration across recovery and
cross-language calls.
