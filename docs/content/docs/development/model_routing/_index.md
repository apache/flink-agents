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

Model routing lets one chat request choose between several registered chat models at runtime. Instead of naming a chat model in a `ChatRequestEvent`, an agent names a **model router**. The router carries a list of **candidate** chat models and a **routing strategy**. For each request the framework runs the strategy, which either **selects** one candidate or **abstains**, meaning it makes no choice and the router's default model is used. The framework then runs the ordinary chat path against the chosen model.

The router only selects a model. The chosen model is invoked by the same `ChatModelAction` that serves a plain chat request, so its prompt, tools, skills, retries, token metrics, and event logging apply the same way. A strategy never calls a model itself. When the decision should come from an LLM, the framework runs that **judge** call through its own chat path, see [LLM Judge](#llm-judge).

Typical uses are sending short requests to a small, cheap model and code, SQL, or multi-step reasoning to a large one; keeping a default model for everything the strategy cannot classify; and falling through to the next candidate when the selected model fails.

What works with routing, and what does not:

- Any registered `CHAT_MODEL` from any provider can be a candidate or a judge. Tool calls work: the model that answered the initial request is kept for every tool-call round of that request.
- Routers are declared with `addResource` on the execution environment or on the agent. There is no annotation for declaring a router inside an agent class, and the YAML API has no section for routers.
- `ReActAgent` cannot use a router; it registers its own chat model under a fixed name. Routing is for agents that send `ChatRequestEvent` themselves.
- A router cannot be a judge (rejected when the plan is built), a candidate of another router, or a chat model's `connection` (both fail at request time).

{{< hint info >}}
Model routing is only supported in Java currently. Python agents cannot register a `MODEL_ROUTER` resource yet and the Python API rejects the attempt with an error. A router declared by a Java agent is still understood when the plan is shared with Python. Python support is planned for a future release.
{{< /hint >}}

## Declaring a Router

A router is a resource of type `ResourceType.MODEL_ROUTER`, built with `ModelRouter.of(...)` and registered like any other resource. Its candidates are the names of chat models registered in the same environment, listed in the order [fallback](#default-model-and-fallback) tries them. A name cannot be both a chat model and a router. The routing types live in `org.apache.flink.agents.api.chat.model.routing`.

{{< tabs "Declaring a Router" >}}

{{< tab "Java" >}}
```java
import org.apache.flink.agents.api.chat.model.routing.ModelRouter;
import org.apache.flink.agents.api.chat.model.routing.Strategies;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceName;
import org.apache.flink.agents.api.resource.ResourceType;

import java.util.LinkedHashMap;
import java.util.Map;

// One Ollama connection shared by the candidates.
agentsEnv.addResource(
        "ollamaConnection",
        ResourceType.CHAT_MODEL_CONNECTION,
        ResourceDescriptor.Builder.newBuilder(ResourceName.ChatModel.OLLAMA_CONNECTION)
                .addInitialArgument("endpoint", "http://localhost:11434")
                .build());

// Two candidate chat models registered under the names "small" and "big".
agentsEnv
        .addResource(
                "small",
                ResourceType.CHAT_MODEL,
                ResourceDescriptor.Builder.newBuilder(ResourceName.ChatModel.OLLAMA_SETUP)
                        .addInitialArgument("connection", "ollamaConnection")
                        .addInitialArgument("model", "qwen3:1.7b")
                        .build())
        .addResource(
                "big",
                ResourceType.CHAT_MODEL,
                ResourceDescriptor.Builder.newBuilder(ResourceName.ChatModel.OLLAMA_SETUP)
                        .addInitialArgument("connection", "ollamaConnection")
                        .addInitialArgument("model", "qwen3:8b")
                        .build());

// Requests whose latest user message mentions code or SQL go to "big";
// everything else abstains and lands on the default, "small".
Map<String, String> rules = new LinkedHashMap<>();
rules.put("big", "\\b(code|sql|program|analyze|prove)\\b");

agentsEnv.addResource(
        "router",
        ResourceType.MODEL_ROUTER,
        ModelRouter.of("small", "big")
                .strategy(Strategies.rules(rules))
                .defaultModel("small")
                .fallback(true)
                .build());
```
{{< /tab >}}

{{< /tabs >}}

The agent then names the router in its `ChatRequestEvent`. Nothing else in the agent changes.

{{< tabs "Using a Router in an Agent" >}}

{{< tab "Java" >}}
```java
public class ModelRoutingAgent extends Agent {

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

| Method | Description |
|--------|-------------|
| `of(String... candidates)` | Start a router over the given chat model names. |
| `strategy(RoutingStrategy)` | Required. One of the `Strategies` factories below. |
| `describe(candidate, description)` | Describe a candidate. Descriptions are the criteria the [LLM judge](#llm-judge) reads. Fails immediately if the name is not a candidate. |
| `defaultModel(String)` | Where the router lands when the strategy abstains. Optional; without it the first candidate is the default. |
| `fallback(boolean)` | Try the remaining candidates, in declaration order, after the selected model fails. Off by default. |
| `build()` | Produces the `ResourceDescriptor` to register. |

To turn routing off, address a candidate directly in the `ChatRequestEvent`, or replace the router registration with a plain chat model under the same name.

## Routing Strategies

A `RoutingStrategy` is a serializable declaration produced by a `Strategies` factory. It travels in the agent plan as a type tag plus arguments; the executor for that type runs on the TaskManager. Every strategy either selects a candidate or abstains.

Selecting a name that is not a candidate is an invalid decision: under `FAIL` and `RETRY` the request fails, under `IGNORE` it is dropped with a warning, and it never falls back to the default. The LLM judge is the exception at request time, because its reply is untrusted model output: a fresh verdict naming a non-candidate abstains. A judge decision replayed from an action-state store is checked like any other persisted decision.

### Rules

`Strategies.rules(Map<String, String>)` maps a candidate name to a regular expression. Rules are evaluated in the map's iteration order against the **content of the most recent user message**; the first candidate whose pattern is found wins, with the decision reason `matched rule: <pattern>`. If nothing matches, or the most recent user message is empty or absent, the strategy abstains. An empty map is allowed and always abstains.

Matching is case-insensitive for ASCII letters; add `(?u)` for Unicode case folding. Rules see only the user message text, not prompt arguments or a bound prompt template. A job that carries its content in prompt arguments and sends an empty user message will always abstain; route on prompt arguments with a [custom executor](#custom-executor) instead. Each pattern is compiled when the strategy is declared, so an invalid expression fails at the `Strategies.rules(...)` call.

{{< tabs "Rule-Based Routing" >}}

{{< tab "Java" >}}
```java
// Order matters when patterns overlap: a LinkedHashMap keeps declaration order,
// Map.of(...) does not guarantee one. The order survives plan serialization.
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

`Strategies.llm(judgeModel)` asks another chat model, the **judge**, to pick the candidate. The judge is a regular `CHAT_MODEL` resource. The framework composes the judge conversation, runs the judge call through the normal chat path, and turns the reply into a decision.

{{< tabs "LLM Judge Routing" >}}

{{< tab "Java" >}}
```java
// The judge is just another registered chat model.
agentsEnv.addResource("judge", ResourceType.CHAT_MODEL, ollamaModel("qwen3:1.7b"));

// The candidate descriptions are the judge's decision criteria.
agentsEnv.addResource(
        "router",
        ResourceType.MODEL_ROUTER,
        ModelRouter.of("small", "big")
                .describe("small", "fast and cheap: chit-chat, lookups, short factual asks")
                .describe("big", "expensive: code, SQL, math, multi-step reasoning")
                .strategy(Strategies.llm("judge"))
                .defaultModel("small")
                .fallback(true)
                .build());
```
{{< /tab >}}

{{< /tabs >}}

`ollamaModel(...)` is a helper in the examples that builds an Ollama setup descriptor bound to that example's connection, like the ones in [Declaring a Router](#declaring-a-router).

The judge must be a **plain chat model** with no prompt, tools, or skills; any of them would break the verdict. Bindings declared in the judge's descriptor are rejected when the plan is built.

- **Input**: The judge receives two messages. A system message lists the candidates with their descriptions and states the verdict format. A user message contains the request, rendered as one `ROLE: content` line per message; messages without content are skipped. When the router's default candidate, or the first candidate if no default is set, binds a prompt template, the judge sees that candidate's rendered request; the answering model still uses its own prompt, so keep templates consistent across candidates when routing should reflect the eventual input. If the request carries no user text, no rendered prompt, and no skill message, its prompt arguments are appended as `Request arguments:` lines.
- **Verdict**: The reply is accepted when it contains `"model": "<candidate name>"` anywhere, or when the whole trimmed reply is exactly a candidate name. Matching is case-sensitive. Non-candidate names are ignored, so instructions hidden in the user's request cannot steer routing outside the declared candidates.
- **Abstain**: A reply that names no candidate, or two or more different candidates, abstains to the default with the reason `judge verdict was not a candidate name`.
- **Failure**: A failed judge call follows the job's error-handling strategy: `FAIL` fails the request, `RETRY` retries the judge call and fails once exhausted, `IGNORE` abstains to the default with the reason `judge call failed: <cause>`. This is more lenient than a throwing rule or custom strategy, see the table below.
- **Custom prompt**: `Strategies.llm(judgeModel, promptTemplate)` replaces the system message. Include `{candidates}` in the template: it is replaced with one `- name: description` line per candidate (`- name` when undescribed), so the judge sees the current candidate list without you repeating it. Nothing else is substituted. The template also owns the verdict instructions.
- **Context budget**: `withMaxContextChars(int)` on an LLM judge declaration limits the conversation history the judge sees, in characters of content. System messages, the rendered prompt, and the newest message are always kept; older messages fill the remaining budget newest-first, and dropped messages set `judge_context_truncated` in the decision metadata. It bounds retained history only, not the judge's total input or token cost.

### Custom Executor

`Strategies.custom(...)` runs your own selection logic. Implement `CustomRoutingExecutor` as a public top-level or public static nested class with a public `(Map<String, Object>)` constructor that receives the declaration's arguments, or a public no-arg constructor. The class is loaded by name at plan construction and on the TaskManagers, so it must be on the classpath in both places. `Strategies.custom(String className, Map)` references it by name.

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

// Declaration: arguments travel with the plan and are handed to the constructor.
ModelRouter.of("small", "big")
        .strategy(Strategies.custom(LengthRoutingExecutor.class, Map.of("threshold", 400)))
        .defaultModel("small")
        .build();
```
{{< /tab >}}

{{< /tabs >}}

`RoutingContext` is a read-only view of the request: `getMessages()`, `getPromptArgs()`, `lastUserMessage()`, `firstUserMessage()`, `getCandidates()` as `RoutingCandidate` objects with name and description, `getDefaultModel()`, `getRouter()`, and `getRequestId()`. Put tenant or workload attributes in the prompt arguments when an executor should route on them. Read-only is a contract: top-level collections are copied, nested values are shared with the request that is sent. The request ID is minted per request and regenerated when Flink re-processes input after a failure, so without an action-state store a hash-based split can land on the other arm on recovery.

`RoutingDecision.of(name)` selects a candidate, `RoutingDecision.builder(name)` adds a reason, score, and metadata that appear on the routing event and the response, and `RoutingDecision.abstain()` defers to the default. `route()` may throw; the exception follows the table below and is never retried.

Custom executors must perform selection only. The context exposes no framework chat API, and a model call from inside `route()` would bypass the framework's retries, token metrics, and event logging; use `Strategies.llm(...)` for that. This is a contract, not an enforced sandbox. An executor is constructed once per declaration per task thread, and construction can run again after recovery, so keep constructors free of external side effects.

## Default Model and Fallback

When the strategy abstains, the router selects `defaultModel`, or the first candidate if none is configured. With `fallback(true)`, the selected model is attempted first; if the attempt fails, the remaining candidates are attempted in declaration order and the first that answers serves the request. Fallback applies to the initial request only. Tool-call rounds keep the model that answered, and a failure in one of those rounds is not routed elsewhere.

An attempt fails when the model call throws, when the candidate name does not resolve to a registered chat model, when the model returns no response, when the provider reports the reply as truncated or content-filtered, or when a requested output schema cannot be parsed. Empty content with tool calls is a normal response. Truncation is only detected for connections that report a finish reason: OpenAI Chat Completions and vLLM, Azure OpenAI, Anthropic, and watsonx. The OpenAI Responses, Bedrock, and Ollama connections do not. A candidate that does not resolve, or whose setup fails to open, is attempted once with no retries.

Retries happen only under `RETRY`; `FAIL` and `IGNORE` make one attempt per candidate. Fallback is a separate setting and applies under every strategy.

| Error-handling strategy | Attempts per candidate | Judge call fails | Rule or custom strategy throws, or selects a non-candidate | Every candidate attempt fails |
|-------------------------|------------------------|------------------|------------------------------------------------------------|-------------------------------|
| `FAIL` | 1 | Request fails | Request fails | Request fails |
| `RETRY` | 1 + `max-retries` for every candidate, with exponential backoff from `retry-wait-interval` | Retried, then request fails | Request fails, no retry | Request fails after each candidate's retries |
| `IGNORE` | 1 | Router abstains to the default model, cause recorded | Request dropped with a warning | Request dropped with a warning |

A dropped request produces no `ChatResponseEvent`. When the request fails, the error raised is the last candidate's, with each earlier error attached as a suppressed exception of the one after it; when more than one candidate was attempted, a warning lists them. A fallback that changes the model is recorded as a second `ModelRoutingEvent` with source `fallback` and in the response's `model_routing` metadata. The retry settings are the job-level options in [Configuration]({{< ref "docs/operations/configuration#core-options" >}}).

## Durability and Replay

Routing runs once per request, on the initial `ChatRequestEvent`. When an action-state store is configured through `actionStateStoreBackend` (see [Action State Store]({{< ref "docs/operations/configuration#action-state-store" >}})), the framework persists three named steps and replays them on recovery instead of recomputing: `route:<router>` holds the decision, `chat:<router>:<candidate>` holds each candidate attempt of the initial request, and `judge:<router>` holds the judge's chat call. Tool-call rounds use the plain `chat` id.

On replay a custom executor's `route()` is skipped and the judge model is not invoked again, though the replayed judge call still emits its lifecycle events and token metrics. Executor construction and framework-side preparation run outside that boundary and can repeat. A strategy exception raised inside the step is persisted too and replayed without running `route()` again, so a transient strategy failure stays failed for that request until the restart strategy gives up. A persisted abstain resolves to the router's current default model; a persisted selection that is no longer a candidate is treated as a non-candidate decision.

Without a store, which is the default, nothing is replayed: the decision and the chat call re-execute together on recovery, and a non-deterministic strategy may pick a different model the second time.

## Observability

Every accepted routing decision emits a `ModelRoutingEvent`, event type `_model_routing_event`, carrying the router, candidates, selected model, decision source, and the strategy's reason and metadata. Subscribe with `@Action(EventType.ModelRoutingEvent)`, or read it from the [Event Log]({{< ref "docs/operations/monitoring#event-log" >}}). The final response message carries a `model_routing` map in its extra arguments, where `final_model` names the model that answered.

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

Decision latency is recorded as the `routingDecisionLatencyMs` histogram and judge calls are metered like any chat call; see [Monitoring]({{< ref "docs/operations/monitoring" >}}). The full event and response fields are listed in the [Reference]({{< ref "docs/development/model_routing/reference" >}}).

## Examples

- [`ModelRoutingExample`](https://github.com/apache/flink-agents/blob/main/examples/src/main/java/org/apache/flink/agents/examples/ModelRoutingExample.java): rule-based routing between two Ollama models.
- [`ModelRoutingJudgeExample`](https://github.com/apache/flink-agents/blob/main/examples/src/main/java/org/apache/flink/agents/examples/ModelRoutingJudgeExample.java): the same two models with an LLM judge and candidate descriptions.
- [`OpenAiModelRoutingExample`](https://github.com/apache/flink-agents/blob/main/examples/src/main/java/org/apache/flink/agents/examples/openai/OpenAiModelRoutingExample.java): rule-based routing between `gpt-4o-mini` and `gpt-4o` over one OpenAI connection.

All three use the [`ModelRoutingAgent`](https://github.com/apache/flink-agents/blob/main/examples/src/main/java/org/apache/flink/agents/examples/agents/ModelRoutingAgent.java) shown above.
