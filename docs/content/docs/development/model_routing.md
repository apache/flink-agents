---
title: Model Routing
weight: 5
type: docs
---
<!--
Licensed to the Apache Software Foundation (ASF) under one
or more contributor license agreements.  See the NOTICE file
distributed with this work for additional information
regarding copyright ownership.  The ASF licenses this file
to you under the Apache License, Version 2.0 (the
"License"); you may not use this file except in compliance
with the License.  You may obtain a copy of the License at

  http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing,
software distributed under the License is distributed on an
"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
KIND, either express or implied.  See the License for the
specific language governing permissions and limitations
under the License.
-->

# Model Routing

## Overview

Model routing lets one chat request choose between several registered chat models at runtime. Instead of naming a chat model in a `ChatRequestEvent`, an agent names a **model router**. The router carries a list of **candidate** chat models and a **routing strategy**. For each request the strategy either **selects** one candidate or **abstains**, in which case the router's default model is used. The chosen model is then invoked through the ordinary chat path, so its prompt, tools, skills, retries, token metrics, and event logging apply as usual.

Typical uses are sending short requests to a small, cheap model and code, SQL, or multi-step reasoning to a large one; keeping a default model for everything the strategy cannot classify; and falling through to the next candidate when the selected model fails.

- Any registered chat model from any provider can be a candidate or a judge. Tool calls work: the model that answered the initial request is kept for every tool-call round of that request.
- `ReActAgent` cannot use a router; it registers its own chat model under a fixed name. Routing is for agents that send `ChatRequestEvent` themselves.
- A router cannot be a judge, a candidate of another router, or a chat model's `connection`.

{{< hint info >}}
Model routing is only supported in Java currently. Python agents cannot register a `MODEL_ROUTER` resource yet, and the YAML API has no section for routers. A router declared by a Java agent is still understood when the plan is shared with Python. Python support is planned for a future release.
{{< /hint >}}

## Quick Start

Declare the connection, the candidate chat models, and any judge with the usual `@ChatModelConnection` and `@ChatModelSetup` annotations. The router itself is a resource of type `ResourceType.MODEL_ROUTER`, built with `ModelRouter.of(...)`; there is no annotation for it yet, so register it with `addResource` in the agent's constructor. Candidates are the names of chat models registered in the same agent, listed in the order [fallback](#default-model-and-fallback) tries them. A name cannot be both a chat model and a router.

{{< tabs "Model Routing Quick Start" >}}

{{< tab "Java" >}}
```java
import org.apache.flink.agents.api.chat.model.routing.ModelRouter;
import org.apache.flink.agents.api.chat.model.routing.Strategies;

public class ModelRoutingAgent extends Agent {

    /** One Ollama connection shared by the candidates. */
    @ChatModelConnection
    public static ResourceDescriptor ollamaConnection() {
        return ResourceDescriptor.Builder.newBuilder(ResourceName.ChatModel.OLLAMA_CONNECTION)
                .addInitialArgument("endpoint", "http://localhost:11434")
                .build();
    }

    /** Candidate "small": a cheap model for everyday requests. */
    @ChatModelSetup
    public static ResourceDescriptor small() {
        return ResourceDescriptor.Builder.newBuilder(ResourceName.ChatModel.OLLAMA_SETUP)
                .addInitialArgument("connection", "ollamaConnection")
                .addInitialArgument("model", "qwen3:1.7b")
                .build();
    }

    /** Candidate "big": a stronger model for code and analysis. */
    @ChatModelSetup
    public static ResourceDescriptor big() {
        return ResourceDescriptor.Builder.newBuilder(ResourceName.ChatModel.OLLAMA_SETUP)
                .addInitialArgument("connection", "ollamaConnection")
                .addInitialArgument("model", "qwen3:8b")
                .build();
    }

    public ModelRoutingAgent() {
        // Requests whose latest user message mentions code or SQL go to "big";
        // everything else abstains and lands on the default, "small".
        Map<String, String> rules = new LinkedHashMap<>();
        rules.put("big", "\\b(code|sql|program|analyze|prove)\\b");

        addResource(
                "router",
                ResourceType.MODEL_ROUTER,
                ModelRouter.of("small", "big")
                        .strategy(Strategies.rules(rules))
                        .defaultModel("small")
                        .fallback(true)
                        .build());
    }

    /** Send each input to the router, which selects the concrete model. */
    @Action(EventType.InputEvent)
    public static void processInput(InputEvent event, RunnerContext ctx) {
        ctx.sendEvent(
                new ChatRequestEvent(
                        "router",
                        Collections.singletonList(
                                new ChatMessage(MessageRole.USER, (String) event.getInput()))));
    }

    /** Emit the model's answer as output. */
    @Action(EventType.ChatResponseEvent)
    public static void processChatResponse(ChatResponseEvent event, RunnerContext ctx) {
        ctx.sendEvent(new OutputEvent(event.getResponse().getContent()));
    }
}
```
{{< /tab >}}

{{< /tabs >}}

Resources can equally be registered on the execution environment with `agentsEnv.addResource(name, type, descriptor)`, as the [examples](#examples) do. The agent names the router in its `ChatRequestEvent`; nothing else in the agent changes. To turn routing off, address a candidate directly, or replace the router registration with a plain chat model under the same name.

| Method | Description |
|--------|-------------|
| `of(String... candidates)` | Start a router over the given chat model names. |
| `strategy(RoutingStrategy)` | Required. One of the `Strategies` factories below. |
| `describe(candidate, description)` | Describe a candidate. Descriptions are the criteria the [LLM judge](#llm-judge) reads. The name must be a candidate. |
| `defaultModel(String)` | Where the router lands when the strategy abstains. Optional; without it the first candidate is the default. Must be a candidate. |
| `fallback(boolean)` | Try the remaining candidates, in declaration order, after the selected model fails. Off by default. |
| `build()` | Produces the `ResourceDescriptor` to register. |

## Routing Strategies

A strategy is declared with a `Strategies` factory and travels with the agent plan. Every strategy either selects a candidate or abstains. A rule or custom strategy that selects a name that is not a candidate is an error: the request fails, or is dropped under `IGNORE`, and never falls back to the default. The LLM judge is the exception, because its reply is untrusted model output: a verdict naming a non-candidate abstains.

### Rules

`Strategies.rules(Map<String, String>)` maps a candidate name to a regular expression. Rules are evaluated in the map's iteration order against the **content of the most recent user message**; the first candidate whose pattern is found wins. If nothing matches, or the most recent user message is empty or absent, the strategy abstains. An empty map is allowed and always abstains.

Matching is case-insensitive for ASCII letters; add `(?u)` for Unicode case folding. Rules see only the user message text, not prompt arguments or a bound prompt template; route on prompt arguments with a [custom executor](#custom-executor) instead. An invalid pattern or a rule key that is not a candidate fails at declaration.

{{< tabs "Rule-Based Routing" >}}

{{< tab "Java" >}}
```java
// Order matters when patterns overlap: a LinkedHashMap keeps declaration order,
// Map.of(...) does not guarantee one.
Map<String, String> rules = new LinkedHashMap<>();
rules.put("big", "\\b(code|sql|program|analyze|prove)\\b");
rules.put("medium", "\\b(summari[sz]e|translate)\\b");

ModelRouter.of("small", "medium", "big")
        .strategy(Strategies.rules(rules))
        .defaultModel("small")
        .build();
```
{{< /tab >}}

{{< /tabs >}}

### LLM Judge

`Strategies.llm(judgeModel)` asks another chat model, the **judge**, to pick the candidate. The judge is a regular chat model declared with `@ChatModelSetup` and must be a **plain** one: no prompt, tools, or skills, since any of them would break the verdict. The candidate descriptions are the judge's decision criteria.

{{< tabs "LLM Judge Routing" >}}

{{< tab "Java" >}}
```java
/** The judge is just another registered chat model. */
@ChatModelSetup
public static ResourceDescriptor judge() {
    return ResourceDescriptor.Builder.newBuilder(ResourceName.ChatModel.OLLAMA_SETUP)
            .addInitialArgument("connection", "ollamaConnection")
            .addInitialArgument("model", "qwen3:1.7b")
            .build();
}

public ModelRoutingAgent() {
    addResource(
            "router",
            ResourceType.MODEL_ROUTER,
            ModelRouter.of("small", "big")
                    .describe("small", "fast and cheap: chit-chat, lookups, short factual asks")
                    .describe("big", "expensive: code, SQL, math, multi-step reasoning")
                    .strategy(Strategies.llm("judge"))
                    .defaultModel("small")
                    .fallback(true)
                    .build());
}
```
{{< /tab >}}

{{< /tabs >}}

- **Input**: The judge receives a system message listing the candidates with their descriptions and the verdict format, and a user message with the request rendered as one `ROLE: content` line per message. When the default candidate binds a prompt template, the judge sees the request rendered through it.
- **Verdict**: The reply is accepted when it contains `"model": "<candidate name>"`, or when the whole trimmed reply is exactly a candidate name. Matching is case-sensitive. Names that are not candidates are ignored, so instructions hidden in the user's request cannot steer routing outside the declared candidates.
- **Abstain**: A reply that names no candidate, or several different candidates, abstains to the default.
- **Failure**: A failed judge call follows the job's error-handling strategy: `FAIL` fails the request, `RETRY` retries the judge call, `IGNORE` abstains to the default with the cause recorded in the routing event.
- **Custom prompt**: `Strategies.llm(judgeModel, promptTemplate)` replaces the system message. Include `{candidates}` in the template; it is replaced with one `- name: description` line per candidate. The template also owns the verdict instructions.
- **Context budget**: `withMaxContextChars(int)` on an LLM judge declaration limits the conversation history the judge sees, in characters. System messages and the newest message are always kept; older messages fill the remaining budget newest-first, and dropped messages are flagged in the decision metadata.

### Custom Executor

`Strategies.custom(...)` runs your own selection logic. Implement `CustomRoutingExecutor` as a public class with a public `(Map<String, Object>)` constructor that receives the declaration's arguments, or a public no-arg constructor. The class must be on the classpath of the client and the TaskManagers.

{{< tabs "Custom Routing Executor" >}}

{{< tab "Java" >}}
```java
public class LengthRoutingExecutor implements CustomRoutingExecutor {

    private final int threshold;

    public LengthRoutingExecutor(Map<String, Object> args) {
        // Arguments arrive from the plan, so numbers may be Integer, Long or Double.
        this.threshold = ((Number) args.getOrDefault("threshold", 400)).intValue();
    }

    @Override
    public RoutingDecision route(RoutingStrategy strategy, RoutingContext context) {
        int length = context.lastUserMessage().length();
        if (length > threshold) {
            // The last declared candidate is the strongest model in this router.
            List<RoutingCandidate> candidates = context.getCandidates();
            String strongest = candidates.get(candidates.size() - 1).getName();
            return RoutingDecision.builder(strongest)
                    .reason("request longer than " + threshold + " characters")
                    .score(length)
                    .build();
        }
        return RoutingDecision.abstain();
    }
}

// Declaration: the arguments are handed to the constructor.
ModelRouter.of("small", "big")
        .strategy(Strategies.custom(LengthRoutingExecutor.class, Map.of("threshold", 400)))
        .defaultModel("small")
        .build();
```
{{< /tab >}}

{{< /tabs >}}

`RoutingContext` is a read-only view of the request: the messages, prompt arguments, last and first user message, the candidates with their descriptions, the default model, the router name, and the request ID. Put tenant or workload attributes in the prompt arguments when an executor should route on them. `RoutingDecision.of(name)` selects a candidate, `RoutingDecision.builder(name)` adds a reason, score, and metadata that appear on the routing event and the response, and `RoutingDecision.abstain()` defers to the default. An exception thrown by `route()` fails the request and is never retried.

Custom executors must only select. The context exposes no chat API; when the decision should come from a model, use `Strategies.llm(...)` so the call gets the framework's retries, metrics, and events. Keep constructors free of side effects: an executor may be constructed more than once, for example after recovery.

## Default Model and Fallback

When the strategy abstains, the router selects `defaultModel`, or the first candidate if none is configured. With `fallback(true)`, the selected model is attempted first; if the attempt fails, the remaining candidates are attempted in declaration order and the first that answers serves the request. Fallback applies to the initial request only. Tool-call rounds keep the model that answered.

An attempt fails when the model call throws, when the candidate does not resolve to a registered chat model, when the model returns no response, when the connection reports the reply as truncated or content-filtered, or when a requested output schema cannot be parsed. Empty content with tool calls is a normal response.

Retries happen only under `RETRY`; `FAIL` and `IGNORE` make one attempt per candidate. Fallback is a separate setting and applies under every strategy.

| Error-handling strategy | Attempts per candidate | Judge call fails | Rule or custom strategy throws, or selects a non-candidate | Every candidate attempt fails |
|-------------------------|------------------------|------------------|------------------------------------------------------------|-------------------------------|
| `FAIL` | 1 | Request fails | Request fails | Request fails |
| `RETRY` | 1 + `max-retries` for every candidate, with exponential backoff from `retry-wait-interval` | Retried, then request fails | Request fails, no retry | Request fails after each candidate's retries |
| `IGNORE` | 1 | Router abstains to the default model, cause recorded | Request dropped with a warning | Request dropped with a warning |

A dropped request produces no `ChatResponseEvent`. When the request fails, the error raised is the last candidate's, with the earlier errors attached as suppressed exceptions. The retry settings are the job-level options in [Configuration]({{< ref "docs/operations/configuration#core-options" >}}).

## Observability

Every routing decision emits a `ModelRoutingEvent`, event type `_model_routing_event`. Subscribe with `@Action(EventType.ModelRoutingEvent)`, or read it from the [Event Log]({{< ref "docs/operations/monitoring#event-log" >}}). A fallback that changes the model emits a second event with source `fallback`.

| Event field | Description |
|-------------|-------------|
| `request_id`, `router`, `candidates` | The request, the router that handled it, and its candidates. |
| `selected_model` | The model the decision selected. |
| `decision_source` | `strategy` for a rule or custom selection, `llm_judge` for a judge verdict, `default` when the strategy abstained, `fallback` on the second event. |
| `fallback_enabled` | Whether fallback is configured, not whether it happened. |
| `reason`, `score`, `metadata` | Set by the strategy. Rules record `matched rule: <pattern>`; the judge records `judge_model`, the judge's token counts when reported, and `judge_context_truncated` when history was dropped. |
| `decision_ms` | Decision latency. For the judge it includes the judge call and its retries. |

The final response message carries a `model_routing` map in its extra arguments with the same fields plus `final_model`, the model that answered, `fallback_attempted`, and `fallback_models_tried`.

{{< tabs "Reading Routing Results" >}}

{{< tab "Java" >}}
```java
@Action(EventType.ChatResponseEvent)
public static void onChatResponse(ChatResponseEvent event, RunnerContext ctx) {
    Object routing = event.getResponse().getExtraArgs().get("model_routing");
    if (routing instanceof Map) {
        Object finalModel = ((Map<?, ?>) routing).get("final_model");
        LOG.info("answered by {}", finalModel);
    }
    ctx.sendEvent(new OutputEvent(event.getResponse().getContent()));
}
```
{{< /tab >}}

{{< /tabs >}}

Decision latency is recorded as the `action.chat_model_action.routingDecisionLatencyMs` histogram, one sample per decision. Judge calls are metered like any chat call under the judge's model id, for example `action.chat_model_action.model.qwen3:1.7b.promptTokens`; a judge that shares a model id with a candidate shares its counters, so use the event's `judge_*` metadata to separate them. See [Monitoring]({{< ref "docs/operations/monitoring" >}}).

## Advanced

**Validation.** Declaration mistakes such as an invalid rule pattern, an unknown candidate in a rule or description, or a missing strategy fail at the builder calls. Whether the judge is a plain chat model and whether a custom executor class can be loaded is checked when the plan is built, before any record is processed. Whether the default model is a candidate and whether each candidate resolves to a registered chat model is only checked on the TaskManager, when the router first handles a request; under `IGNORE` such a router drops every request with a warning, so verify a new router under `FAIL` first.

**Recovery.** Routing runs once per request. When an [action state store]({{< ref "docs/operations/configuration#action-state-store" >}}) is configured, the routing decision, the judge call, and each candidate attempt of the initial request are persisted and replayed on recovery, so a custom executor or judge is not run again for a request that already has a decision. Without a store, which is the default, the decision is recomputed on recovery, and a non-deterministic strategy may pick a different model the second time. The request ID is regenerated in that case too, so a hash-based split in a custom executor can land on the other arm.

## Examples

- [`ModelRoutingExample`](https://github.com/apache/flink-agents/blob/main/examples/src/main/java/org/apache/flink/agents/examples/ModelRoutingExample.java): rule-based routing between two Ollama models.
- [`ModelRoutingJudgeExample`](https://github.com/apache/flink-agents/blob/main/examples/src/main/java/org/apache/flink/agents/examples/ModelRoutingJudgeExample.java): the same two models with an LLM judge and candidate descriptions.
- [`OpenAiModelRoutingExample`](https://github.com/apache/flink-agents/blob/main/examples/src/main/java/org/apache/flink/agents/examples/openai/OpenAiModelRoutingExample.java): rule-based routing between `gpt-4o-mini` and `gpt-4o` over one OpenAI connection.

All three register their resources on the execution environment and use the [`ModelRoutingAgent`](https://github.com/apache/flink-agents/blob/main/examples/src/main/java/org/apache/flink/agents/examples/agents/ModelRoutingAgent.java) actions shown above.
