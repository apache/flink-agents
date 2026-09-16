---
title: Reference
weight: 1
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

# Model Routing Reference

Reference material for [Model Routing]({{< ref "docs/development/model_routing" >}}): event and response fields, metrics, validation stages, and descriptor keys.

## Routing Event and Response Fields

Event attributes: `request_id`, `router`, `candidates`, `selected_model`, `decision_source`, `fallback_enabled` (configured, not whether fallback happened), the strategy's `reason`, `score`, and `metadata`, and `decision_ms`. `decision_source` is `strategy` for a rule or custom selection, `llm_judge` for a judge verdict, `default` when the strategy abstained, including a judge that could not decide or failed under `IGNORE`, and `fallback` on the second event. Rules record `matched rule: <pattern>` as the reason; the judge records `judge_model`, `judge_prompt_tokens` and `judge_completion_tokens` when the connection reports them, and `judge_context_truncated` in the metadata when messages were dropped. For the judge, `decision_ms` includes the judge call, its retries, and backoff; on replay it is the original latency.

The final response message carries a `model_routing` map in its extra arguments with `router`, `candidates`, `selected_model`, `final_model`, `decision_source`, `fallback_enabled`, `fallback_attempted`, `fallback_models_tried`, `reason`, `score`, and `metadata`. After a fallback, `decision_source` here is `fallback` and the original source survives only in the first event.

## Metrics

Decision latency is recorded as the `action.chat_model_action.routingDecisionLatencyMs` histogram, one sample per decision including rejected and replayed ones. Judge calls are metered like any chat call under the provider model id the judge connection reports, for example `action.chat_model_action.model.qwen3:1.7b.promptTokens`; a judge that shares a model id with a candidate shares its counter, so use the event's `judge_*` metadata to separate them. Judge retries count toward the routed request's retry fields and the retry metrics under the judge's connection name. See [Monitoring]({{< ref "docs/operations/monitoring" >}}).

## Validation

Declaration errors surface at the declaration calls (`Strategies.*`, `withMaxContextChars`, `describe`) or at `build()`: an empty judge model name, an empty prompt template, a non-positive context budget, an invalid or null rule pattern, a rule key or `describe(...)` name that is not a candidate, and a missing strategy. Registering a name as both a chat model and a router fails at `addResource`.

At plan construction, before any record is processed, the plan checks that the judge model is a registered `CHAT_MODEL` with no prompt, tools, or skills bound; that a custom executor class is on the classpath, implements `CustomRoutingExecutor`, and has a supported constructor, without instantiating it; that no name is both a chat model and a router, when the plan is built from an `Agent` class; and, for hand-built descriptors, that `strategy_type` is present and a string and `candidates` is a list.

Two checks only run when the router is instantiated on the TaskManager: that it has candidates with no duplicates, and that the default model is a candidate. Because a router that fails to construct is never cached, these fail on every routed request; under `IGNORE` each request is dropped and the warning shows only the wrapping `InvocationTargetException`, so verify a new router under `FAIL` first. Whether each candidate name resolves to a registered `CHAT_MODEL` is not validated before the first request; an unresolvable candidate counts as a failed attempt.


## Descriptor Keys

The builder writes these arguments into the `ResourceDescriptor`, which matter when a plan is inspected as JSON or built by hand: `candidates` (list, in fallback order), `candidate_descriptions` (map, present only when set), `default_model`, `fallback` (boolean), `strategy_type` (`rule_based`, `llm_judge`, or `custom`), `strategy_args` (`rules`; or `judge_model` with optional `prompt_template` and `max_context_chars`; or the custom executor's constructor arguments), and `strategy_executor_class` for custom strategies. The resource type's wire value is `model_router`.

