---
title: "Context Management"
weight: 9
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

## Overview

A chat request that uses tools sends its whole conversation to the model on every
round of the [tool loop]({{< ref "docs/development/calling-llms/chat#built-in-events-and-actions" >}}),
including every earlier tool result. On a long loop the input grows with each round.
It costs more, and at some point it no longer fits the model's context window.

A **context management policy** bounds what the built-in chat Action sends to the
model. It has three parts, and each part is off unless it is set:

| Part | What it does | Extra model call |
|------|--------------|------------------|
| Tool result truncation | Cuts a tool result that is longer than a limit and keeps its beginning and its end. | No |
| Tool result masking | Sends the tool results of the most recent rounds as they are and replaces the results of older rounds with a one-line placeholder. | No |
| Summarization | When the estimated input exceeds a share of a token budget, replaces older rounds with a summary that a chat model writes, one line for each round. | Yes |

A **round** is one model reply that requests tool calls, together with the results
of those calls. Only rounds are truncated, masked or summarized. System, user and
plain assistant messages are always sent as they are. So is what a
[skill]({{< ref "docs/development/calling-llms/skills" >}}) returns, see
[Skills](#skills).

A policy changes only what the model is sent. The conversation the framework keeps
for the request stays complete. Context management is off by default: without a
policy the model is sent the whole conversation, as before.

## Declare the Default Policy

The `context-management.*` options declare the default policy of every chat request
of an Agent. Set them on the environment or in the Flink YAML configuration file;
see [Context management]({{< ref "docs/operations/configuration#context-management" >}})
for every option.

{{< tabs "Default Policy" >}}
{{< tab "Python" >}}
```python
config = agents_env.get_config()
# Cut tool results that are longer than 4000 characters.
config.set(AgentExecutionOptions.CONTEXT_MANAGEMENT_TOOL_RESULTS_MAX_CHARS, 4000)
# Keep the tool results of the last 3 rounds and mask the older ones.
config.set(AgentExecutionOptions.CONTEXT_MANAGEMENT_TOOL_RESULTS_KEEP_LAST_ROUNDS, 3)
# Summarize older rounds when the input passes 80% of 100000 tokens.
config.set(AgentExecutionOptions.CONTEXT_MANAGEMENT_MAX_INPUT_TOKENS, 100000)
```
{{< /tab >}}
{{< tab "Java" >}}
```java
Configuration config = agentsEnv.getConfig();
// Cut tool results that are longer than 4000 characters.
config.set(AgentExecutionOptions.CONTEXT_MANAGEMENT_TOOL_RESULTS_MAX_CHARS, 4000);
// Keep the tool results of the last 3 rounds and mask the older ones.
config.set(AgentExecutionOptions.CONTEXT_MANAGEMENT_TOOL_RESULTS_KEEP_LAST_ROUNDS, 3);
// Summarize older rounds when the input passes 80% of 100000 tokens.
config.set(AgentExecutionOptions.CONTEXT_MANAGEMENT_MAX_INPUT_TOKENS, 100000);
```
{{< /tab >}}
{{< tab "YAML" >}}
```yaml
agent:
  context-management:
    tool-results:
      max-chars: 4000
      keep-last-rounds: 3
    max-input-tokens: 100000
```
{{< /tab >}}
{{< /tabs >}}

An option that is out of range, or that has no effect without another option, fails
the job when it is submitted. The message names the option.

## Set a Policy on a Request

A chat request can carry its own policy. It replaces the default policy as a whole
for that request: a part the request's policy does not set is off, not inherited.
`ContextManagementPolicy.disabled()` switches context management off for one
request.

{{< tabs "Request Policy" >}}
{{< tab "Python" >}}
```python
policy = ContextManagementPolicy(
    tool_result_max_chars=2000,
    tool_result_keep_last_rounds=2,
)

# Chat event.
ctx.send_event(ChatRequestEvent(
    model="review_model",
    messages=[ChatMessage.user("Summarize the open orders.")],
    context_management=policy,
))

# ctx.chat.
result = await ctx.chat(
    "review_model",
    [ChatMessage.user("Summarize the open orders.")],
    context_management=policy,
)
```
{{< /tab >}}
{{< tab "Java" >}}
```java
ContextManagementPolicy policy =
        ContextManagementPolicy.builder()
                .toolResultMaxChars(2000)
                .toolResultKeepLastRounds(2)
                .build();

// Chat event.
ctx.sendEvent(
        new ChatRequestEvent(
                        "reviewModel",
                        List.of(ChatMessage.user("Summarize the open orders.")))
                .withContextManagement(policy));

// ctx.chat.
ChatResult result =
        ctx.chat(
                        new ChatRequestEvent(
                                        "reviewModel",
                                        List.of(ChatMessage.user("Summarize the open orders.")))
                                .withContextManagement(policy))
                .await();
```
{{< /tab >}}
{{< /tabs >}}

| Python field | Java builder method | Configuration option |
|--------------|---------------------|----------------------|
| `tool_result_max_chars` | `toolResultMaxChars` | `context-management.tool-results.max-chars` |
| `tool_result_keep_last_rounds` | `toolResultKeepLastRounds` | `context-management.tool-results.keep-last-rounds` |
| `tool_result_mask_step_rounds` | `toolResultMaskStepRounds` | `context-management.tool-results.mask-step-rounds` |
| `max_input_tokens` | `maxInputTokens` | `context-management.max-input-tokens` |
| `summarization_trigger_ratio` | `summarizationTriggerRatio` | `context-management.summarization.trigger-ratio` |
| `summarization_keep_last_rounds` | `summarizationKeepLastRounds` | `context-management.summarization.keep-last-rounds` |
| `summarizer` | `summarizer` | `context-management.summarization.model` |

## Tool Result Truncation

A tool result with more characters than `tool-results.max-chars` is cut in the
middle. Its beginning and its end are kept, and a marker tells the model how much is
missing:

```text
account=7 amount=70 audit=xxxxxxxxxxxxxxxx
...[2035 characters truncated]...
xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
```

The text that is sent, marker included, is never longer than the limit. Sizes are
counted in characters, not in bytes or tokens. Media blocks of a tool result are
kept, and text that follows a media block still follows it. The smallest limit is
100.

## Tool Result Masking

With `tool-results.keep-last-rounds` set to `N`, the tool results of the last `N`
rounds are sent as they are. The result of an older round is replaced with a
placeholder:

```text
[Tool result omitted by context management: lookup({"account":7}) returned 3026 characters]
```

The model's tool call stays in the conversation, so the model still sees what it
asked for and that the call was answered. Arguments longer than 200 characters are
cut in the placeholder. Media blocks of a masked result are not sent; the placeholder
counts them.

A result is masked only when that makes it shorter. A result without media that is
not longer than its placeholder is sent as it is, or cut to `tool-results.max-chars`
when that is set. With `tool-results.max-chars` set, a placeholder that would be
longer than the limit leaves out the call and keeps the size:

```text
[Tool result omitted by context management: 3026 characters]
```

So with `tool-results.max-chars` set, no tool result that is sent is longer than the
limit, whether it is masked or not. The results of `load_skill` are the exception,
see [Skills](#skills).

{{< hint warning >}}
The model cannot read a masked result again. Use masking when later rounds do not
depend on the content of earlier results, or when the model states what it found in
its own replies, which are always sent. When the answer depends on earlier results,
for example a total over every result, use [summarization](#summarization), which
asks a chat model to keep what the request still needs.
{{< /hint >}}

`tool-results.mask-step-rounds` moves the boundary in steps. With a step of `S`,
masking waits until `S` rounds beyond the kept ones are eligible and then masks them
together. The default step is 1: every model call masks one more round.

For example, with `keep-last-rounds: 3` and `mask-step-rounds: 5`:

| Rounds so far | Masked rounds |
|---------------|---------------|
| 1 to 7 | none |
| 8 to 12 | 1 to 5 |
| 13 to 17 | 1 to 10 |

Providers that cache a repeated input prefix charge less for cached input tokens.
Masking a round changes a message in the middle of the input, so the provider reads
everything after it as new input once. With a step of 1 this happens on every model
call; a larger step makes it happen once for each step. Truncation does not have this
effect: a result is cut the same way every time it is sent. New lines of a summary
change the message the summary is in, with the same effect on the input after it.

## Summarization

`max-input-tokens` is the input budget of one model call. Before each model call the
Action estimates the input it is about to send. When the estimate exceeds
`summarization.trigger-ratio` of the budget (0.8 by default) and more rounds than
`summarization.keep-last-rounds` (3 by default) are not summarized yet, the older
rounds are replaced with a summary:

1. The last `summarization.keep-last-rounds` rounds are sent as they are.
2. The rounds before them are no longer sent. A summary of them takes the place of
   the last of these rounds, so it follows every message that came before them. It
   is added to the user message right before that place, or sent as a user message
   of its own when there is none. The summary has one line for each round:

   ```text
   [Summary of the earlier tool-call rounds of this request (rounds 1 to 3), which are no longer shown]
   Round 1: account 1 holds 10, the next account is 4
   Round 2: account 4 holds 40, the next account is 2
   Round 3: account 2 holds 20, the next account is 6
   ```
3. When the input passes the trigger again, the lines of the next rounds are added
   to the summary.

The lines are written by the chat model named by `summarization.model`, or by the
chat model that answers the request when the option is not set. The summarizer is
sent the instructions and the user text of the request and the stored rounds to
summarize, with results cut to `tool-results.max-chars` when that is set. It is
asked for one line for each round, with what the round found and the request still
needs. The instructions are the system messages the chat model is sent for the
request, its bound prompt included, so that the summarizer can tell what the request
still needs. The instructions and the user text are each cut to an eighth of the
budget. Each result is shown with the tool call it answers. The summarizer itself is
called without the chat model's prompt and tools.

Only the lines of the rounds the summarizer was shown are kept. A line for another
round, a total or a conclusion it adds is left out, and a line that is far longer
than asked is cut. A line is written once, from its own round, and is never
rewritten: a later summarization cannot change or drop what an earlier one kept.

The summarizer is asked to keep a line within 30 words, or more with a budget above
6000 tokens, up to 150 words. Rounds are shown in batches that fit the budget, ten
rounds at most. A single round that does not fit is cut further for the summarizer
and is summarized by itself.

Keep these points in mind:

- The budget triggers summarization. It is not a hard limit: the request's own
  messages and the kept rounds are always sent, and once only the kept rounds are
  left nothing more is summarized.
- The summary may take half of the budget. When it has no room for the line of
  another round, no more rounds are summarized, and each model call reports that. A
  request can summarize from a few dozen to a few hundred rounds, depending on the
  budget and on how long the lines are.
- A summary is an extra model call. It is reported as an LLM call of its own under
  the summarizer's resource name and counted in the
  [token usage metrics]({{< ref "docs/operations/monitoring#token-usage-metrics" >}}).
  It follows `chat.async` and, with durable execution, its recorded answer is reused
  after a recovery like a chat call's. `max-retries` does not apply to it.
- A summary that cannot be written is skipped: the summarizer cannot be used, the
  call fails, the provider did not finish the answer, or the answer has no line for
  a round it was asked about. The request goes on with its rounds as they are, a
  warning is logged, and the next model call tries again. When an answer for several
  rounds leaves one out, the rounds are first asked about again, one at a time.
- A budget of a few hundred tokens is too small for a summarizer request, which
  holds the summarizer's own instructions, the request's instructions and text and
  at least one round. Nothing is summarized then, and each model call reports why.
- Size the budget so that the messages that are never summarized fit under the
  trigger: the request's own messages, the summary, the kept rounds and the rounds
  that load a [skill](#skills). When they are over the trigger by themselves, every
  model call summarizes the round that just left the kept ones, which is one
  summarizer call for each round.
- A line is written by a chat model and can leave out or misstate something the
  request needs. Keep more recent rounds with `summarization.keep-last-rounds`, or
  name a stronger chat model with `summarization.model`, when the answer depends on
  exact values from many rounds.
- The summarizer has to be declared in the same language as the Agent. A Java Agent
  cannot use a chat model declared in Python to write summaries, and a Python Agent
  cannot use one declared in Java. Set `summarization.model` to a chat model of the
  Agent's language in that case. The bound prompt of a chat model that is declared
  in the other language is not shown to the summarizer.

## Skills

The built-in `load_skill` tool returns the instructions and the files of a
[skill]({{< ref "docs/development/calling-llms/skills" >}}), which the model has to
keep following. A policy never changes them:

- The result of a `load_skill` call is not truncated and not masked, whatever its
  size.
- A round that calls `load_skill` is not summarized. It stays in the input when the
  rounds around it are replaced with a summary, and the summary's heading says so.
- The other results of such a round, when the model called another tool in the same
  reply, are not masked either, because no summary would hold them. They are still
  cut to `tool-results.max-chars`.

These results count toward the estimated input like every other message.

## How the Parts Combine

The parts are applied to the stored conversation each time the model is called:

1. Rounds that the summary covers are not sent, except rounds that load a
   [skill](#skills).
2. Of the other rounds, those beyond `tool-results.keep-last-rounds` are masked. The
   count includes the summarized rounds, so the boundary does not move when rounds
   are summarized.
3. The remaining tool results are truncated when they are longer than
   `tool-results.max-chars`.

Truncation adds no model call and loses only the middle of oversized results, so it
is a good first step. Masking adds no model call either, but the model loses the
masked results. Summarization costs a model call and asks a chat model to keep what
the request still needs; use it for long tool loops whose answer depends on earlier
results.

## Token Estimates

The estimate comes from the chat model's connection and includes the bound prompt
and tools. For a chat model that is declared in the other language than the Agent, it
covers the messages only. The built-in connections estimate from the size of the
request: about one
token for four ASCII characters, one token for every other character, and a fixed
cost for each message, tool definition and media block. This is an approximation.
After each model call of the request the Action compares it with the input size the
provider reported. When the reported size is larger, the request's later estimates
are raised by that factor, up to 4 times. The factor comes from the model call with
the largest input the provider reported for the request. A call that reports less
than an earlier one does not change it, because some providers report cached input
tokens separately. With a
[model router]({{< ref "docs/development/calling-llms/model_routing" >}}), the report
of a candidate that answered by fallback is not used, because the estimate was made
for the selected model.

A connection whose provider can count tokens can override `estimate_input_tokens`
(Python) or `estimateInputTokens` (Java) and return an exact count, which is used as
it is. The method is called before every model call of a request that has a budget,
so it has to be fast and return the same count for the same request.

## Observability

The Action emits a `ContextManagementEvent` for every step it takes: once when a
result is first sent truncated, once when rounds are first masked, and once when a
summarization runs. It does not emit one for every model call.

| Event field | Description |
|-------------|-------------|
| `request_id` | The chat request. |
| `model` | The chat model resource the request is sent to. For a [model router]({{< ref "docs/development/calling-llms/model_routing" >}}), the selected model. |
| `step` | `truncate`, `mask` or `summarize`. |
| `details` | What the step did, see below. |

| Step | Details |
|------|---------|
| `truncate` | `max_chars`, and `results` with the `call_id`, `tool`, `original_chars` and `sent_chars` of each truncated result. |
| `mask` | `keep_last_rounds`, `step_rounds`, `rounds` (how many rounds this step masked), and `results` with the `call_id`, `tool` and `omitted_chars` of each masked result. |
| `summarize` | `summarizer`, `rounds` (how many lines this step added to the summary, one for each round), `summarized_rounds` (how many rounds the summary stands for; rounds among them that load a skill are still sent and have no line), `max_input_tokens`, `estimated_input_tokens_before`, `estimate_approximate`, and, when rounds were added, `summary_chars` and `estimated_input_tokens_after`. `error` holds the reason when the summary could not be written. |

For event-based requests, subscribe an Action to `EventType.ContextManagementEvent`
or inspect [Trace Log]({{< ref "docs/operations/monitoring#trace-log" >}}). In Trace
Log, these Events have `entityType: event` and
`entityName: _context_management_event`; their fields are under `attributes`.

The Events of a `ctx.chat` call stay inside the call, like its other Events. They do
not trigger Actions, and Trace Log records them as `_internal_subgraph` records whose
`delegate_type` is `_context_management_event`, with the fields under
`delegate_attributes`.

## Limitations

- A policy applies to the tool loop of one chat request. Tool-call rounds that
  arrive in the request's messages are treated like the rounds of the loop. Other
  messages are never changed, so a request whose own messages exceed the model's
  context window still fails.
- With a [model router]({{< ref "docs/development/calling-llms/model_routing" >}}),
  the policy is applied once for each model call, for the selected model. A candidate
  that is tried by fallback is sent the same messages.
- Summaries are written by a chat model of the Agent's language, see
  [Summarization](#summarization).
