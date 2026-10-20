---
title: "Model Routing"
weight: 4
type: docs
aliases:
  - /docs/development/calling-llms/declare-resources/model_routing/
  - /docs/development/model_routing/
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

<span id="model-routing"></span>

## Overview

A **model router** selects a chat model for each request from a list of registered
**candidates**. For example, route code and SQL questions to a larger model and
use a smaller model for everyday requests. A **routing strategy** selects a
candidate or **abstains** (makes no selection), in which case the router uses its
default model.

Use the router's resource name in a `ChatRequestEvent` or a Java `ctx.chat` call.
The selected model uses its own prompt, tools, skills, and generation settings.
Routing can also try another candidate if the initial model call fails.

{{< hint info >}}
Routers currently support the Java Programmatic API: register them on an Agent or
its execution environment. There is no router declaration annotation, Python
registration API, or YAML router section. The built-in `ReActAgent` does not
accept a router in place of its chat model setup.
{{< /hint >}}

<span id="quick-start"></span>

## Register a Router

Register the candidate [chat model setups]({{< ref "docs/development/calling-llms/chat-models" >}})
and their connections first. Candidate names refer to `CHAT_MODEL` Resources
available to the Agent, including those registered on its execution environment.
See [Resources]({{< ref "docs/development/create-agent/resources#registering-resources" >}})
for registration methods and scopes.

The example below assumes two setups named `small` and `big`. It sends requests
mentioning code or SQL to `big` and uses `small` when no rule matches. Add this
constructor to your Agent class:

```java
public ModelRoutingAgent() {
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
```

`ModelRouter.of(...).build()` returns a `ResourceDescriptor`. Register it as a
`MODEL_ROUTER` Resource using the
[Programmatic API]({{< ref "docs/development/create-agent/resources#programmatic-api" >}}).
The complete [examples](#examples) demonstrate registration on the execution
environment.

| Builder method | Required | Description |
|----------------|----------|-------------|
| `of(String... candidates)` | Required | At least one registered chat model name. Names must be non-empty and unique. Candidate order determines the fallback order. |
| `strategy(RoutingStrategy)` | Required | One of the `Strategies` factories described below. |
| `describe(candidate, description)` | Optional | Describes when to choose a candidate, particularly for an LLM judge. The name must be a candidate. |
| `defaultModel(String)` | Optional | Candidate to use when the strategy abstains. Defaults to the first candidate. |
| `fallback(boolean)` | Optional | Try remaining candidates after the selected model fails. Defaults to `false`. |
| `build()` | Required | Validate the declaration and produce the descriptor. |

A resource name cannot identify both a chat model and a router. Candidates and
judges must be chat model setups; a router cannot serve as another router's
candidate or judge, or as a chat model connection.

## Routing Strategies

Choose rules for text matching, an LLM judge for semantic selection, or a custom
executor for application-specific logic. All strategies use the same
[selection and failure rules](#default-model-and-fallback).

### Rules

`Strategies.rules(Map<String, String>)` maps candidate names to regular
expressions. It checks the **text of the most recent user message** in map
iteration order and selects the first matching candidate. An empty rule map,
an absent or empty user message, or no match causes abstention.

Rules use Java `java.util.regex.Pattern` with `Pattern.CASE_INSENSITIVE`.
Matching uses `Matcher.find()`: a matching substring is sufficient. Use `\A...\z`
when the rule should match the whole message. Case-insensitive
matching covers ASCII letters by default; add `(?u)` for Unicode case folding.
Backslashes must be escaped in Java string literals: `"\\bcode\\b"` represents
`\bcode\b`, matching `code` at word boundaries.

```java
// LinkedHashMap preserves priority when patterns overlap.
Map<String, String> rules = new LinkedHashMap<>();
rules.put("big", "\\b(code|sql|program|analyze|prove)\\b");
rules.put("medium", "\\b(summari[sz]e|translate)\\b");

ModelRouter.of("small", "medium", "big")
        .strategy(Strategies.rules(rules))
        .defaultModel("small")
        .build();
```

This example also requires a registered `medium` setup. `Map.of(...)` does not
guarantee rule order. Rules see user-message text before a candidate's prompt is
applied; use a [custom executor](#custom-executor) to route on prompt arguments.
Invalid patterns and rule targets outside the candidate list fail validation.

### LLM Judge

`Strategies.llm(judgeModel)` asks a registered chat model to choose a candidate
based on the candidate descriptions and the request. The judge setup must have
no bound prompt, tools, or skills. You can use an existing setup that meets these
requirements; the example below declares a separate one using an existing Ollama
connection:

```java
@ChatModelSetup
public static ResourceDescriptor judge() {
    return ResourceDescriptor.Builder.newBuilder(ResourceName.ChatModel.OLLAMA_SETUP)
            .addInitialArgument("connection", "ollamaConnection")
            .addInitialArgument("model", "qwen3:1.7b")
            .build();
}
```

Replace the rule-based router descriptor with:

```java
ModelRouter.of("small", "big")
        .describe("small", "fast and cheap: short factual questions and everyday requests")
        .describe("big", "code, SQL, math, and multi-step reasoning")
        .strategy(Strategies.llm("judge"))
        .defaultModel("small")
        .fallback(true)
        .build();
```

The judge receives the conversation's text and media, including tool calls and
results, but excluding reasoning blocks. If the default candidate has a prompt,
its rendered messages are included in the judge's input. When no usable text or
media is present, prompt arguments provide the input instead. Choose a judge
provider and model that support the request's media types.

A verdict is accepted when it contains `"model": "<candidate name>"`, or its entire
trimmed text equals a candidate name. Matching is case-sensitive. Non-candidate
names are ignored; a reply with no recognized candidate or multiple distinct
candidates causes abstention. A failed judge call is a request failure after
retries are exhausted; it does not select the default.

Two optional settings customize the judge:

| Setting | Description |
|---------|-------------|
| `Strategies.llm(judgeModel, promptTemplate)` | Supply the judge's system instructions. Include `{candidates}` to insert one `- name: description` line per candidate, and instruct the judge to return the verdict format above. |
| `withMaxContextChars(int)` | Limit conversation text, including tool calls and results. System messages, rendered prompt messages, and the newest message are always kept; older messages are included newest-first as space allows. Media stays with its message and does not count toward the character limit. Required messages can exceed the limit. |

For example, `.strategy(Strategies.llm("judge").withMaxContextChars(8000))` sets a
text budget. This is not a token limit or a hard cap on total input.

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

`RoutingContext` is a read-only view of the request: the messages, prompt arguments, last and first user message, the candidates with their descriptions, the default model, the router name, and the request ID. Put tenant or workload attributes in the prompt arguments when an executor should route on them. `RoutingDecision.of(name)` selects a candidate, `RoutingDecision.builder(name)` adds a reason, score, and metadata that appear on the routing event and the response, and `RoutingDecision.abstain()` defers to the default. An exception thrown by `route()` fails the request; the strategy itself is not retried.

Custom executors must only select. The context exposes no chat API; when the decision should come from a model, use `Strategies.llm(...)` so the call gets the framework's retries, metrics, and events. Keep constructors free of side effects: an executor may be constructed more than once, for example after recovery.

## Call a Router

Use the registered router name wherever a chat call takes a model name. The
following fragments run inside an Action:

{{< tabs "Call a Model Router" >}}
{{< tab "Chat events" >}}
```java
ctx.sendEvent(new ChatRequestEvent(
        "router", List.of(ChatMessage.user("Write a SQL query to count orders."))));
```

Handle the result in an Action subscribed to `ChatResponseEvent`.
{{< /tab >}}
{{< tab "ctx.chat" >}}
```java
ChatResult result = ctx.chat(
        "router", List.of(ChatMessage.user("Write a SQL query to count orders.")))
        .await();
```

Use the result in the same Action. Java awaits require the runtime
configuration described in [Using ctx.chat]({{< ref "docs/development/calling-llms/chat#using-ctx-chat" >}}).
{{< /tab >}}
{{< /tabs >}}

Both paths handle the selected model's tool calls automatically. The model that
answers the initial call remains selected throughout the tool-call loop.
To bypass routing, call a candidate by its registered name. See
[Chat with Models]({{< ref "docs/development/calling-llms/chat" >}}) for message
construction, structured output, and response handling.

## Default Model and Fallback

The default model handles requests the strategy cannot select a model for.
Fallback handles failures while calling a selected candidate. These are separate
behaviors:

| Situation | Result |
|-----------|--------|
| Strategy selects a candidate | Call that candidate. |
| Strategy abstains, or returns a name outside the candidates | Call `defaultModel`, or the first candidate if no default is configured. |
| Declaration has an invalid pattern, non-candidate rule target or default, or duplicate/empty candidates | Reject the configuration before processing requests. |
| Custom strategy throws, or the judge call exhausts retries | Fail the request; do not treat it as abstention or try candidate fallback. |
| Candidate call fails with fallback disabled | Fail the request after applicable retries. |
| Candidate call fails with fallback enabled | Try the remaining candidates in declaration order, stopping at the first successful response. |
| All attempted candidates fail | Fail the request with the last candidate's error; earlier errors remain in diagnostics. |

For candidates `[small, medium, big]`, selecting `big` with fallback enabled tries
`big`, then `small`, then `medium`. Model-call retries run before fallback; see
[Model calls, tools, and retries]({{< ref "docs/operations/configuration#model-calls-tools-and-retries" >}}).
Failures before the call, such as an unresolved model resource, can proceed
directly to the next candidate without retries.

Candidate failures include call exceptions, an unresolved model resource, a null
response, a response reported as truncated or content-filtered, and failure to
produce the requested structured output. A valid response containing tool calls
counts as a successful initial response. Once the tool-call loop starts, later failures do
not switch to another candidate.

For event-based requests, failures arrive as failed `ChatResponseEvent` instances.
For `ctx.chat`, awaiting a failed call throws. See
[Handle Success and Failure]({{< ref "docs/development/calling-llms/chat#handle-success-and-failure" >}})
for handling either case.

## Observability

The router emits a `ModelRoutingEvent` after selecting a model, including when
abstention selects the default. If a later candidate successfully answers the
initial call, it emits another event with `decision_source: fallback`. Failed
candidate attempts do not each emit a routing event.

For event-based requests, subscribe with `@Action(EventType.ModelRoutingEvent)`
or inspect [Trace Log]({{< ref "docs/operations/monitoring#trace-log" >}}).
In Trace Log, these Events have `entityType: event` and
`entityName: _model_routing_event`; their fields are under `attributes`.

| Event field | Description |
|-------------|-------------|
| `request_id`, `router`, `candidates` | Request identity, router resource name, and candidate names. |
| `selected_model` | Selected chat model resource name. On a fallback event, the candidate that successfully answered. |
| `decision_source` | `strategy` for rules/custom selection, `llm_judge` for a judge selection, `default` for abstention, or `fallback`. |
| `fallback_enabled` | Whether fallback is configured, not whether it happened. |
| `reason`, `score`, `metadata` | Strategy diagnostics. Judge metadata can include `judge_model`, token counts, and `judge_context_truncated`. |
| `decision_ms` | Decision latency, including judge calls and retries. Absent on the fallback event. |

When a custom or replayed decision selects a non-candidate, normalization records
`rejected_model` and, if present, `rejected_reason` in metadata and drops the score.
An unrecognized fresh judge verdict instead reports the reason
`judge verdict was not a candidate name`.

A **successful** final `ChatResponseEvent` for a routed request carries a `model_routing` attribute
with `selected_model`, `final_model`, `fallback_attempted`, and
`fallback_models_tried`, plus the router and decision details. Failed responses
do not carry this attribute. For example, in a response Action:

```java
if (event.isFailed()) {
    throw new IllegalStateException(event.getError());
}
Map<?, ?> routing = (Map<?, ?>) event.getAttr("model_routing");
if (routing != null) {
    String finalModel = (String) routing.get("final_model");
    // Use the registered name of the model that answered.
}
```

`ctx.chat` returns only `ChatResult`, without the response Event's routing
attributes. Its internal routing events do not trigger the caller's Action
listeners.

Routing decision latency and model token metrics are described in
[Monitoring]({{< ref "docs/operations/monitoring" >}}). Judge and candidate calls
sharing the same provider model ID share token counters; use the judge event
metadata when distinguishing their usage.

<span id="advanced"></span>

## Recovery

With durable execution enabled, completed routing decisions and model calls can
be reused after recovery. Without it, a request may be routed again and select a
different model. See
[Consistency]({{< ref "docs/operations/consistency#recovery-guarantees-and-user-responsibilities" >}})
for the persistence and replay guarantees.

## Examples

- {{< gh_link file="examples/src/main/java/org/apache/flink/agents/examples/ModelRoutingExample.java" name="`ModelRoutingExample`" >}}: rule-based routing between two Ollama models.
- {{< gh_link file="examples/src/main/java/org/apache/flink/agents/examples/ModelRoutingJudgeExample.java" name="`ModelRoutingJudgeExample`" >}}: the same two models with an LLM judge and candidate descriptions.
- {{< gh_link file="examples/src/main/java/org/apache/flink/agents/examples/openai/OpenAiModelRoutingExample.java" name="`OpenAiModelRoutingExample`" >}}: rule-based routing between `gpt-4o-mini` and `gpt-4o` over one OpenAI connection.

All three register their resources on the execution environment and use the {{< gh_link file="examples/src/main/java/org/apache/flink/agents/examples/agents/ModelRoutingAgent.java" name="`ModelRoutingAgent`" >}} to send requests and handle responses.
