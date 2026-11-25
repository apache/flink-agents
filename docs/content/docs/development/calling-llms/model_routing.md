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

Use the router's resource name in a `ChatRequestEvent` or a `ctx.chat` call.
The selected model uses its own prompt, tools, skills, and generation settings.
Routing can also try another candidate if the initial model call fails, and can
[escalate](#quality-triggered-escalation) a request to a stronger model when the
answer it received does not pass a quality check.

{{< hint info >}}
Routers are declared with the Programmatic API or YAML in Java and Python.
Register them on an Agent or its execution environment. Python supports the rules
and custom strategies; the LLM judge, decision model strategies, and quality-triggered
escalation are Java only.
Java annotations and Python decorators are not supported. The built-in `ReActAgent`
does not accept a router in place of its chat model setup.
{{< /hint >}}

<span id="quick-start"></span>

## Register a Router

Register the candidate [chat model setups]({{< ref "docs/development/calling-llms/chat-models" >}})
and their connections first. Candidate names refer to `CHAT_MODEL` Resources
available to the Agent, including those registered on its execution environment.
See [Resources]({{< ref "docs/development/create-agent/resources#registering-resources" >}})
for registration methods and scopes.

### Programmatic API

The example below assumes two setups named `small` and `big`. It sends requests
mentioning code or SQL to `big` and uses `small` when no rule matches. Add this
to your Agent class:

{{< tabs "Register a Router" >}}
{{< tab "Python" >}}
```python
from flink_agents.api.chat_models.routing import ModelRouter, Strategies
from flink_agents.api.resource import ResourceType


def __init__(self) -> None:
    super().__init__()
    self.add_resource(
        "router",
        ResourceType.MODEL_ROUTER,
        ModelRouter.of("small", "big")
        .strategy(Strategies.rules({"big": r"\b(code|sql|program|analyze|prove)\b"}))
        .default_model("small")
        .fallback(enabled=True)
        .build(),
    )
```
{{< /tab >}}
{{< tab "Java" >}}
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
{{< /tab >}}
{{< /tabs >}}

`ModelRouter.of(...).build()` returns a `ResourceDescriptor`. Register it as a
`MODEL_ROUTER` Resource using the
[Programmatic API]({{< ref "docs/development/create-agent/resources#programmatic-api" >}})
or the [YAML declaration](#yaml) below.
The complete [examples](#examples) demonstrate programmatic registration on the
execution environment.

| Builder method | Required | Description |
|----------------|----------|-------------|
| `of(String... candidates)` | Required | At least one registered chat model name. Names must be non-empty and unique. Candidate order determines the fallback order. |
| `strategy(RoutingStrategy)` | Unless escalation is configured (Java) | One of the `Strategies` factories described below. |
| `describe(candidate, description)` | Optional | Describes when to choose a candidate, particularly for an LLM judge. The name must be a candidate. |
| `defaultModel(String)` | Optional | Candidate to use when the strategy abstains. Defaults to the first candidate. |
| `fallback(boolean)` | Optional | Try remaining candidates after the selected model fails. Defaults to `false`. |
| `escalation(Escalation)` | Optional, Java only | Upgrade to a stronger model when an answer fails a [quality check](#quality-triggered-escalation). |
| `build()` | Required | Validate the declaration and produce the descriptor. |

Python uses `default_model(name)` and `fallback(enabled=...)`; it does not yet
support `escalation(...)`.

<span id="yaml"></span>

### YAML API

Declare a router under `model_routers`, with `clazz` set to `router`. The
following fields configure its routing behavior.

| Field | Required | Description |
|-------|----------|-------------|
| `candidates` | Required | Chat model setup names. Names must be non-empty and unique. Order is the fallback order. |
| `strategy_type` | Unless `escalation_tiers` is configured (Java) | `rule_based`, `custom`, or, on Java, `llm_judge` or `decision_model`. |
| `strategy_args` | Depends on the strategy | Rule map, judge or decision-model settings, or custom executor arguments. |
| `strategy_executor_class` | Custom only | Fully qualified custom executor class. |
| `candidate_descriptions` | Optional | Map of candidate name to description. |
| `default_model` | Optional | Candidate used when the strategy abstains. When omitted, the first candidate is used. |
| `fallback` | Optional | Try the remaining candidates after the selected model fails. Defaults to `false`. |

A declaration is checked when the agent plan is built. An invalid candidate list,
rule, default model, or strategy fails with the same error as the same descriptor
registered programmatically. A strategy the runtime does not support, such as
`llm_judge` or `decision_model` on Python, fails at that same check.

Java YAML requires `type: java`.

```yaml
agents:
  - name: model_routing_agent
    model_routers:
      - name: router
        clazz: router
        candidates: [small, big]
        candidate_descriptions:
          small: Everyday requests
          big: Code, SQL, and analysis
        strategy_type: rule_based
        strategy_args:
          rules:
            big: '\b(code|sql|program|analyze|prove)\b'
        default_model: small
        fallback: true
```

See [YAML]({{< ref "docs/development/create-agent/yaml" >}}) for the general
declaration rules.

A chat model and a model router registered on the same agent must have different
names.

An internal sub-agent inherits resources, including chat models and routers,
from its parent agent (the agent that contains it). If the sub-agent registers
its own chat model or router with the same name, that definition takes precedence.
For example, if the parent agent registers a router named `assistant` and the
sub-agent registers a chat model named `assistant`, a chat request to `assistant`
inside the sub-agent calls its own chat model without using the parent's router.

Candidates and judges must be chat model setups; a router cannot serve as another
router's candidate or judge, or as a chat model connection.

## Routing Strategies

Choose rules for text matching, an LLM judge or a decision model for semantic
selection, or a custom executor for application-specific logic. All strategies
use the same [selection and failure rules](#default-model-and-fallback).

### Rules

`Strategies.rules(...)` maps candidate names to regular
expressions. It checks the **text of the most recent user message** in map
iteration order and selects the first matching candidate. An empty rule map,
an absent or empty user message, or no match causes abstention. In YAML,
`strategy_args.rules` maps a candidate name to a regular expression.

A matching substring is sufficient; anchor the pattern (`\A...\z` in Java,
`\A...\Z` in Python) when the rule should match the whole message. Matching
is case-insensitive for ASCII letters.

{{< tabs "Rules" >}}
{{< tab "Python" >}}
Rules use `re.search` with `re.IGNORECASE | re.ASCII`; a `dict` keeps rule order.
Case folding is ASCII only; Unicode flags such as `(?u)` are rejected.
Patterns must also be valid Java regular expressions, so avoid Python-only syntax
such as named groups (`(?P<name>...)`).

```python
ModelRouter.of("small", "medium", "big").strategy(
    Strategies.rules({
        "big": r"\b(code|sql|program|analyze|prove)\b",
        "medium": r"\b(summari[sz]e|translate)\b",
    })
).default_model("small").build()
```
{{< /tab >}}
{{< tab "Java" >}}
Rules use `java.util.regex.Pattern` with `Pattern.CASE_INSENSITIVE` and
`Matcher.find()`; add `(?u)` for Unicode case folding. Backslashes must be escaped
in Java string literals: `"\\bcode\\b"` represents `\bcode\b`. `Map.of(...)`
does not guarantee rule order.

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
{{< /tab >}}
{{< /tabs >}}

This example also requires a registered `medium` setup. Rules see user-message
text before a candidate's prompt is
applied; use a [custom executor](#custom-executor) to route on prompt arguments.
Invalid patterns and rule targets outside the candidate list fail validation.

### LLM Judge

Python doesn't support this strategy yet.

In YAML, `strategy_args.judge_model` names a registered chat-model setup.

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
| `withMaxContextChars(int)` | Limit conversation text, including tool calls and results. Media and messages added by the anchor model are outside the character limit. |

For example, `.strategy(Strategies.llm("judge").withMaxContextChars(8000))` sets a
text budget. This is not a token limit or a hard cap on total input.

System messages and the newest message are always kept. Older messages are
included newest-first as space allows. The anchor model’s bound prompt and
skill-discovery messages are added after this limit is applied. Media stays with
its message and does not count toward the character limit. Required messages can
exceed the limit.

### Decision Model

Python doesn't support this strategy yet.

In YAML, `strategy_args.decision_model` names a registered decision-model setup.

`Strategies.decision(decisionModel)` asks a registered
[decision model]({{< ref "docs/development/decision_models" >}}) one choice
question: the options are the candidate names and the criteria are their
descriptions. The answer carries a probability for every candidate, so the
router gets a confidence for its decision and can decline to route on a weak
one. Register the decision model setup and its connection first; every candidate
must have a description.

```java
ModelRouter.of("small", "big")
        .describe("small", "short factual questions and everyday requests")
        .describe("big", "code, SQL, math, and multi-step reasoning")
        .strategy(Strategies.decision("dm").withMinProbability(0.6))
        .defaultModel("small")
        .fallback(true)
        .build();
```

The decision model receives the conversation as text, including tool calls and
results, rendered as for the LLM judge. Media and reasoning blocks are left out,
and the number of media blocks dropped is recorded in the decision metadata.
When the request carries no text at all, for example a media-only message, the
prompt arguments are appended to the input as the request, so this fallback can
apply where the judge, which also accepts media, sends the message itself. With
no prompt arguments either, the strategy abstains to the default model without
calling the decision model. The chosen candidate becomes the selected
model, and its reported probability becomes the routing `score`. The probability
is the provider's value for the chosen candidate; the framework does not
normalize the distribution.

The strategy abstains, and the default model answers, when the answer is a
refusal, when it is not a choice, when the result carries no answer for the
question, or when the chosen candidate's probability is below
`withMinProbability`. A provider may omit the chosen candidate from the
probability map: with `withMinProbability` set that is an abstention too, since
there is nothing to compare; without it the choice routes with no `score`. For
either abstention, the choice and the full probability map stay in the
metadata. A router using this strategy needs at least two candidates. A decision call that fails after the configured retries is a request
failure, like a failed judge call; it does not select the default or try
candidate fallback.

| Setting | Description |
|---------|-------------|
| `Strategies.decision(decisionModel, instructions)` | Replace the built-in instruction for the choice question. |
| `withMinProbability(double)` | Abstain to the default model when the chosen candidate's probability is below this value, between 0 and 1 inclusive. |
| `withMaxContextChars(int)` | Limit the conversation text with the same rules as for the LLM judge. |

The decision model's `provider_confidence`, when the provider reports one, is
recorded in the metadata but is not used for the threshold, since not every
provider returns it. Built-in decision providers are Python, so a Java job that
uses one needs the Python environment on the TaskManagers, as for any Java
decision call.

### Custom Executor

`Strategies.custom(...)` runs your own selection logic. Implement `CustomRoutingExecutor` with a constructor that receives the declaration's arguments as a map, or a constructor with no arguments. The class must be available on the client and the TaskManagers.

{{< tabs "Custom Routing Executor" >}}
{{< tab "Python" >}}
```python
from typing import Any

from flink_agents.api.chat_models.routing import (
    CustomRoutingExecutor,
    ModelRouter,
    RoutingContext,
    RoutingDecision,
    RoutingStrategy,
    Strategies,
)


class LengthRoutingExecutor(CustomRoutingExecutor):
    def __init__(self, args: dict[str, Any]) -> None:
        self.threshold = args.get("threshold", 400)

    def route(
        self, strategy: RoutingStrategy, context: RoutingContext
    ) -> RoutingDecision:
        length = len(context.last_user_message())
        if length > self.threshold:
            # The last declared candidate is the strongest model in this router.
            return RoutingDecision(
                context.candidates[-1].name,
                reason=f"request longer than {self.threshold} characters",
                score=length,
            )
        return RoutingDecision.abstain()


# Declaration: the arguments are handed to the constructor.
ModelRouter.of("small", "big").strategy(
    Strategies.custom(LengthRoutingExecutor, {"threshold": 400})
).default_model("small").build()
```

The class must be importable by its module and class name.
{{< /tab >}}
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

The class and its constructor must be public.
{{< /tab >}}
{{< /tabs >}}

`RoutingContext` is a read-only view of the request: the messages, prompt arguments, last and first user message, the candidates with their descriptions, the default model, the router name, and the request ID. Put tenant or workload attributes in the prompt arguments when an executor should route on them. `RoutingDecision.of(name)` selects a candidate, `RoutingDecision.builder(name)` in Java or the `RoutingDecision(name, reason=..., score=..., metadata=...)` constructor in Python adds a reason, score, and metadata that appear on the routing event and the response, and `RoutingDecision.abstain()` defers to the default. An exception thrown by `route()` fails the request; the strategy itself is not retried.

Custom executors must only select. The context exposes no chat API; when the decision should come from a model, use `Strategies.llm(...)` or `Strategies.decision(...)` in Java so the call gets the framework's retries, metrics, and events. Keep constructors free of side effects: an executor may be constructed more than once, for example after recovery.

## Call a Router

Use the registered router name wherever a chat call takes a model name. The
following fragments run inside an Action:

{{< tabs "Call a Model Router" >}}
{{< tab "Python" >}}
```python
# Chat event: handle the result in an Action subscribed to ChatResponseEvent.
ctx.send_event(ChatRequestEvent(
    model="router",
    messages=[ChatMessage.user("Write a SQL query to count orders.")],
))

# ctx.chat: use the result in the same async Action.
result = await ctx.chat(
    "router", [ChatMessage.user("Write a SQL query to count orders.")]
)
```
{{< /tab >}}
{{< tab "Java" >}}
```java
// Chat event: handle the result in an Action subscribed to ChatResponseEvent.
ctx.sendEvent(new ChatRequestEvent(
        "router", List.of(ChatMessage.user("Write a SQL query to count orders."))));

// ctx.chat: use the result in the same Action.
ChatResult result = ctx.chat(
        "router", List.of(ChatMessage.user("Write a SQL query to count orders.")))
        .await();
```

Java awaits require the runtime configuration described in
[Using ctx.chat]({{< ref "docs/development/calling-llms/chat#using-ctx-chat" >}}).
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
| Custom strategy throws, or the judge or decision call exhausts retries | Fail the request; do not treat it as abstention or try candidate fallback. |
| Decision model refuses, answers something other than a choice, or its chosen candidate is below `withMinProbability` or has no reported probability while `withMinProbability` is set | Call `defaultModel`, or the first candidate if no default is configured. |
| Decision model's chosen candidate has no reported probability and no `withMinProbability` is set | Call the chosen candidate; the routing event carries no `score`. |
| Candidate call fails with fallback disabled | Fail the request after applicable retries. |
| Candidate call fails with fallback enabled | Try the remaining candidates in declaration order, stopping at the first successful response. |
| All attempted candidates fail | Fail the request. Java reports the last candidate's error and keeps earlier errors as suppressed exceptions; Python reports every attempted candidate's error in one message. |

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

<span id="quality-triggered-escalation"></span>

## Quality-Triggered Escalation

Use **escalation** to start with a cheaper model and switch to a stronger one
when its answer fails a quality check. List the models as **tiers**, from cheapest
to strongest. A rejected answer is discarded, and the next tier receives the
conversation. Tool calls from a rejected answer are not executed.

Escalation is currently supported in Java only. This example assumes registered
chat model setups named `small` and `big`, and a [decision model]({{< ref "docs/development/decision_models" >}})
named `verifier`:

```java
agentsEnv.addResource(
        "router",
        ResourceType.MODEL_ROUTER,
        ModelRouter.of("small", "big")
                .escalation(
                        Escalation.tiers("small", "big")
                                .when(QualitySignals.decision("verifier", 0.7)))
                .build());
```

Requests start on `small`. If the verifier estimates a probability below `0.7`
that its answer satisfies the request, the router asks `big` instead.

### Quality signals

Built-in checks require no configuration. They detect empty answers, unknown
tools, invalid tool arguments, and identical tool calls repeated in consecutive
responses to the same user turn. For Python-declared candidate models, checks
for tool availability and argument validity are skipped.

Add application-specific checks with either kind of signal below. Built-in checks
run first, then signals in declaration order, stopping at the first rejection.
Checks run only while the current model can escalate to a higher tier.

#### Decision model signal

`QualitySignals.decision(name, minProbability)` asks a decision model whether
the answer correctly and completely addresses the request. A probability below
`minProbability` triggers escalation. To use your own evaluation criteria, pass
instructions as the third argument.

The verifier receives the conversation and answer. It skips responses containing
only tool calls and evaluates the answer after the tools return. A verifier
refusal or a response that does not answer the yes/no question does not trigger
escalation.

#### Custom signal

Implement `QualitySignal.evaluate(QualityContext)` to inspect the conversation,
answer, and current tier, then return `QualityVerdict.accept()` or
`QualityVerdict.escalate(reason)`. Keep this evaluation deterministic and free of
side effects; use a decision model signal when evaluation requires a model call.

```java
public class SqlAnswerSignal implements QualitySignal {
    @Override
    public QualityVerdict evaluate(QualityContext context) {
        String answer = context.getResponse().getText();
        boolean sqlRequest = context.lastUserMessage().toLowerCase().contains("sql");
        if (sqlRequest && !answer.toLowerCase().contains("select")) {
            return QualityVerdict.escalate("a SQL request was answered without a statement");
        }
        return QualityVerdict.accept();
    }
}
```

Register it with `.when(QualitySignals.custom(SqlAnswerSignal.class))`. The class
must have a public no-argument constructor or a public constructor taking a `Map`.
To pass configuration, use `QualitySignals.custom(className, args)` and read the
arguments in the `Map` constructor.
Custom signals also evaluate responses containing tool calls.

### Configuration and behavior

| Setting | Behavior |
|---------|----------|
| `Escalation.tiers(...)` | At least two distinct candidates, ordered from cheapest to strongest. Models outside this list do not escalate. |
| `strategy(...)` | Optional with escalation. Selects the starting model; otherwise use `defaultModel`, or the first tier when no default is set. |
| `maxEscalations(n)` | Maximum upgrades across the whole request, including tool calls. Defaults to the number of tiers minus one. |

After an upgrade, subsequent tool-call responses use the stronger model and can
escalate further. If an answer cannot satisfy the requested output schema, it
can also trigger escalation. Invalid schema configuration still fails the request.

| Situation | Result |
|-----------|--------|
| An answer fails a check and another upgrade is allowed | Discard the answer and try the next tier. |
| The last tier or upgrade limit is reached | Stop quality checks. Model calls and schema validation can still fail. |
| A verifier exhausts its retries, or a custom signal throws | Fail the request. |
| The upgraded model fails | Apply [fallback](#default-model-and-fallback) during the initial call if enabled, skipping candidates whose answers failed a quality check. During the tool-call loop, or with no candidate left, fail the request. |

When combining fallback and escalation, a lower tier reached by fallback can
escalate back to a higher tier that already failed, spending its retry budget
again. This limitation is tracked in [#1424](https://github.com/apache/flink-agents/issues/1424).

## Observability

The router emits a `ModelRoutingEvent` after selecting a model, including when
abstention selects the default. If a later candidate successfully answers the
initial call, it emits another event with `decision_source: fallback`. Each
escalation emits an event with `decision_source: escalation` whose
`selected_model` is the tier the request moved to. Failed candidate attempts do
not each emit a routing event.

For event-based requests, subscribe an Action to `EventType.ModelRoutingEvent`
or inspect [Trace Log]({{< ref "docs/operations/monitoring#trace-log" >}}).
In Trace Log, these Events have `entityType: event` and
`entityName: _model_routing_event`; their fields are under `attributes`.

| Event field | Description |
|-------------|-------------|
| `request_id`, `router`, `candidates` | Request identity, router resource name, and candidate names. |
| `selected_model` | Selected chat model resource name. On a fallback event, the candidate that successfully answered. |
| `decision_source` | `strategy` for rules/custom selection, `llm_judge` for a judge selection, `decision_model` for a decision model selection, `default` for abstention, `fallback`, or `escalation`. |
| `fallback_enabled` | Whether fallback is configured, not whether it happened. |
| `reason`, `score`, `metadata` | Explanation, optional score, and diagnostics for the selection. Escalation metadata includes `signal`, `escalated_from`, and `discarded_tokens`. Decision model scores are probabilities. |
| `decision_ms` | Decision latency, including judge or decision model calls and retries. Absent on the fallback event. |

A successful final `ChatResponseEvent` includes a `model_routing` attribute with
the router and decision details, plus the fields below. Failed responses do not
include it.

| Response field | Description |
|----------------|-------------|
| `selected_model`, `final_model` | The initially selected model and the model that produced the final answer. |
| `fallback_attempted`, `fallback_models_tried` | Whether fallback was attempted and which fallback candidates were tried. |
| `escalation_attempted`, `escalations` | Whether escalation occurred and its history across the request, including tool calls. Each record contains `from`, `to`, `signal`, `reason`, optional `score`, and `discarded_tokens`. |
| `escalated_tokens` | Tokens consumed by discarded answers, where usage was reported by the providers. |
| `decision_source` | The last selection mechanism: for example, `escalation`, or `fallback` if another candidate answered after an upgraded model failed. |

For example, read the final model in a response Action:

{{< tabs "Routing Attribute" >}}
{{< tab "Python" >}}
```python
response = ChatResponseEvent.from_event(event)
if response.is_failed:
    raise RuntimeError(response.error)
routing = response.get_attr("model_routing")
if routing is not None:
    final_model = routing["final_model"]
    # Use the registered name of the model that answered.
```
{{< /tab >}}
{{< tab "Java" >}}
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
{{< /tab >}}
{{< /tabs >}}

`ctx.chat` returns only `ChatResult`, without the response Event's routing
attributes. Its internal routing events do not trigger the caller's Action
listeners.

Routing decision latency, escalation counts and model token metrics are
described in [Monitoring]({{< ref "docs/operations/monitoring" >}}). Judge and
candidate calls sharing the same provider model ID share token counters; use the
judge event metadata when distinguishing their usage. A discarded answer's
tokens are still billed and still counted against its model; the
`routingEscalatedTokens` counter and `escalated_tokens` attribute report them
separately.

<span id="advanced"></span>

## Recovery

With durable execution enabled, recorded routing decisions, model calls, and
quality evaluations are reused after recovery. Without it, a request may be
routed again and select a different model. See
[Consistency]({{< ref "docs/operations/consistency#recovery-guarantees-and-user-responsibilities" >}})
for the persistence and replay guarantees.

## Examples

- {{< gh_link file="examples/src/main/java/org/apache/flink/agents/examples/ModelRoutingExample.java" name="`ModelRoutingExample`" >}}: rule-based routing between two Ollama models.
- {{< gh_link file="examples/src/main/java/org/apache/flink/agents/examples/ModelRoutingJudgeExample.java" name="`ModelRoutingJudgeExample`" >}}: the same two models with an LLM judge and candidate descriptions.
- {{< gh_link file="examples/src/main/java/org/apache/flink/agents/examples/ModelRoutingEscalationExample.java" name="`ModelRoutingEscalationExample`" >}}: start with a small Ollama model and upgrade when a custom SQL-answer check fails.
- {{< gh_link file="examples/src/main/java/org/apache/flink/agents/examples/openai/OpenAiModelRoutingExample.java" name="`OpenAiModelRoutingExample`" >}}: rule-based routing between `gpt-4o-mini` and `gpt-4o` over one OpenAI connection.
- {{< gh_link file="examples/src/main/java/org/apache/flink/agents/examples/openai/OpenAiDecisionRoutingExample.java" name="`OpenAiDecisionRoutingExample`" >}}: the same two OpenAI models routed by a decision model (the built-in Python OpenAI decision model, used through the cross-language API), with an agent that reports the selected model, decision source and score.

All of them register their resources on the execution environment. The first two and the OpenAI rules example use the {{< gh_link file="examples/src/main/java/org/apache/flink/agents/examples/agents/ModelRoutingAgent.java" name="`ModelRoutingAgent`" >}} to send requests and handle responses.

