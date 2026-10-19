---
title: Decision Models
weight: 8
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

# Decision Models

A decision model evaluates application-supplied evidence against named questions.
It returns probabilities, a choice, or a score. The business Action supplies facts
and criteria, chooses thresholds, and decides what to do with the answers.

Decision models have Java and Python APIs and can be declared through either YAML
loader. Python Actions use Python connections and setups. Java Actions can use
those providers through the cross-language API, or use native Java providers.
Built-in providers are implemented in Python. Custom Java providers implement
`BaseDecisionModelConnection` and use `BaseDecisionModelSetup` to select a
registered connection by name and a model.

Call a model directly when the same Action will use its answers. Use
[event-driven calls](#event-driven-calls) when you want the built-in Action to
manage durable execution, retries, and execution reports.

## Direct Python call

The following example uses TypeSafe's Jev model. Set `TYPESAFE_API_KEY` in the
environment before running it. The same request can also be used with OpenAI.

```python
from flink_agents.api.decision_models import (
    ChoiceAnswer,
    ChoiceQuestion,
    DecisionRequest,
    RefusalAnswer,
)
from flink_agents.integrations.decision_models.typesafe_decision_model import (
    TypeSafeDecisionModelConnection,
)

connection = TypeSafeDecisionModelConnection()
request = DecisionRequest(
    input="I was charged twice for my order.",
    questions={
        "team": ChoiceQuestion(
            instructions="Which team should handle this ticket?",
            criteria={
                "billing": "Payments, invoices, and refunds.",
                "technical": "Problems using the product.",
                "other": "Requests outside these categories.",
            },
        )
    },
)
try:
    connection.open()
    result = connection.decide(
        request,
        model="jev-latest",
        request_params={"extra_headers": {"x-request-id": "ticket-42"}},
    )
    answer = result.answers["team"]
    if isinstance(answer, RefusalAnswer):
        print("Send to manual review")
    elif isinstance(answer, ChoiceAnswer):
        print(answer.choice)
        for value, probability in answer.probabilities.items():
            print(value, probability)
    print(result.usage)
finally:
    connection.close()
```

A connection owns the SDK client. For standalone use, open the connection, pass
the model to `decide()`, and close the connection when finished. In an Agent,
setups select a registered connection by name and fix the model for subsequent
calls. Multiple setups can share a connection. FA opens registered resources
before use and closes them when finished; an Action should not manage their
lifecycle.

To use OpenAI, select its connection and model. Keep the same request; the
adapter converts the criteria map into the provider's required choices array:

```python
from flink_agents.integrations.decision_models.openai_decision_model import (
    OpenAIDecisionModelConnection,
)

# Reads OPENAI_API_KEY; OPENAI_BASE_URL is also supported by the SDK.
connection = OpenAIDecisionModelConnection()
try:
    connection.open()
    result = connection.decide(request, model="gpt-6-luna")
    print(result.model_dump_json(indent=2))
finally:
    connection.close()
```

For a standard Bailian workspace endpoint, reuse the TypeSafe implementation:

```python
import os

connection = TypeSafeDecisionModelConnection(
    api_key=os.environ["DASHSCOPE_API_KEY"],
    base_url=(
        "https://"
        + os.environ["DASHSCOPE_WORKSPACE_ID"]
        + ".cn-beijing.maas.aliyuncs.com/compatible-mode"
    ),
)
try:
    connection.open()
    result = connection.decide(request, model="decision-model-preview")
finally:
    connection.close()
```

The SDK appends `/v1/systemone` to the TypeSafe API root. Choose a model and
regional endpoint available to your account. The Bailian example follows its
[standard Decision API documentation](https://help.aliyun.com/zh/model-studio/decision-model-api);
other gateway or fine-tuned deployment authentication must be checked separately.

Optional `request_params` passes provider SDK parameters as a string-keyed JSON
map for this call only. It cannot replace the configured model, input, or
questions. Unsupported parameters raise errors rather than being ignored.

## Questions and results

Questions are supplied to each `decide()` call rather than stored in the setup.
A request contains shared input and at least one named question. Question
identifiers and string choice values are case-sensitive. Every question produces
one answer under the same identifier; incomplete or inconsistent responses raise
an error.

The public models use `pydantic.JsonValue` for input, instructions, and
descriptions. This permits JSON strings, objects, arrays, numbers, booleans, and
null recursively, rather than arbitrary Python objects. Invalid content, unknown
fields, and non-finite numbers raise errors. Providers impose additional content
and size restrictions, described in
[Provider conversion rules](#provider-conversion-rules).

| Question | Definition | Answer |
|---|---|---|
| `PredicateQuestion` | Optional instructions and `PredicateCriteria(true=..., false=...)`; at least one non-null instruction or outcome description | `PredicateAnswer.probability_true` |
| `ChoiceQuestion` | Nonempty `dict[str, JsonValue]` of choices and optional instructions | `ChoiceAnswer.choice`, `probabilities: dict[str, float]` |
| `ScoreQuestion` | Nonempty `list[JsonValue]` of level descriptions ordered from lowest to highest, and optional instructions | `ScoreAnswer.score`, `probabilities: dict[int, float]`, `legend: dict[int, JsonValue]` |

`PredicateCriteria.true` and `.false` describe what makes the condition true or
false. Either description may be omitted; an all-null criteria object has the
same meaning as no criteria. The answer always estimates the probability of
true, even when only the false outcome is described. Both adapters accept these
descriptions; OpenAI embeds them in instructions as described below.

`ChoiceQuestion.criteria` maps each string choice value to its description. A
`None` description leaves the value itself to define that option. Keys must be
strings; boolean and numeric keys are rejected rather than converted.
`ChoiceAnswer.choice` is a string, and `probabilities` maps every requested choice
value to its probability.

`ScoreQuestion.criteria` is a list of complete level descriptions. Each entry is
a `JsonValue`: it can be text or structured JSON. Objects are retained as whole
descriptions. A level's **zero-based position** identifies it, so repeated
descriptions are allowed. For example:

```python
from flink_agents.api.decision_models import ScoreQuestion

impact_question = ScoreQuestion(
    instructions="Rate the operational impact of this issue.",
    criteria=[
        "A cosmetic issue that does not affect functionality.",
        {"scope": "Some functions", "workaround": "Available"},
        "All core functions are unavailable.",
    ],
)
```

A score is the expected zero-based level index and can fall between levels.
For probabilities `0.2`, `0.5`, and `0.3` on indices `0`, `1`, and `2`, the score
is `1.1`; FA does not round it or select a winning label.

`ScoreAnswer.probabilities` maps each level index to its probability, and
`ScoreAnswer.legend` maps each index to the **original request's criteria entry**.
The example's `legend[1]` is the complete `{"scope": "Some functions", ...}`
object. Both maps are ordered by ascending level index. Their keys are integers
in Python and become object-key strings in JSON. Reading the JSON with
`DecisionResult.model_validate_json(...)` restores integer indices.

```python
from flink_agents.api.decision_models import ScoreAnswer


def print_score(answer: ScoreAnswer) -> None:
    print(answer.score)
    for index, probability in answer.probabilities.items():
        print(index, answer.legend[index], probability)
```

Responses must cover every requested choice or score level exactly once, with
finite probabilities in `[0, 1]`. Choice answers must select a requested value.
Score answers must lie in `[0, number_of_levels - 1]`. FA preserves the provider's
probabilities and scores without checking probability sums or whether a score
agrees with its probabilities. It does not normalize probabilities, infer missing
entries, or apply business thresholds.

Choice and score answers retain `provider_confidence` when reported. This is the
provider's own measure, not a replacement for the full probability distribution.

`RefusalAnswer` represents an explicit per-question provider refusal. OpenAI
supports this answer type; TypeSafe does not currently define one. Unknown
answer types and malformed responses raise errors. Authentication failures,
timeouts, and other request failures raise exceptions rather than becoming
refusals or low-confidence answers.

`DecisionResult.usage` applies to the whole request. Unreported counts remain
`None`; they are not filled with zero or inferred from other counts.
`model` contains the provider-reported model when available, and `metadata`
retains additional response-level and usage fields, such as request IDs, latency,
and token usage details. There is no required response identifier.

### Provider conversion rules

Use one typed request for all providers. Each adapter translates the common
questions to its provider's format using the rules below. The
[TypeSafe SDK reference](https://docs.typesafe.ai/sdk/python/api/types/questions)
and [OpenAI API reference](https://developers.openai.com/api/reference/resources/decisions/methods/create)
define the native formats.

| FA content | TypeSafe / Jev adapter | OpenAI adapter |
|---|---|---|
| `input` | Text, object, or array becomes `state`; null, numbers, and booleans are rejected | Strings are preserved; other non-null JSON values are encoded as text; null is rejected |
| `instructions` | Text, object, or array; omitted when `None`; unsupported root scalars are rejected | Required for Choice and Score; Predicate may use criteria alone. Strings are preserved and other non-null JSON values are encoded as text |
| Predicate `criteria` | Passes outcome descriptions to SDK `Noul`; omits null outcomes | Combines non-null outcome descriptions with instructions using `yes`/`no`, then JSON-encodes the result |
| Choice `criteria` | Preserves the map; descriptions are text, object, array, or null | Converts each map entry to a choice with its string key as `value`; descriptions are converted to text and omitted when null |
| Score `criteria` | Preserves each complete description and its order; each entry must be text, an object, or an array | Converts each entry to a level with `label=str(index)`; non-null entries become text descriptions and null entries omit `description` |

Strings remain text even when they contain JSON. OpenAI text conversion
JSON-encodes structured content with Unicode preserved. With Predicate criteria,
`true` becomes `yes` and `false` becomes `no`. These keys are merged into object
instructions, taking precedence over existing keys of the same name. Other
non-null instructions are placed under `question`; omitted instructions leave
only the outcome meanings. Absent or all-null criteria leave instructions unchanged.

Generated score labels identify level indices, while `ScoreAnswer.legend`
retains the original descriptions. Jev receives the criteria list with its
structure and order preserved.

TypeSafe Choice and Score questions can be defined by their criteria alone.
A Predicate still needs instructions or at least one described outcome.

| Request constraint | TypeSafe / Jev | OpenAI |
|---|---|---|
| Question count | No adapter-specific limit | 1–200 |
| Choice count | 1–255 | 2–255 |
| Score level count | 1–10 | 2–10 |

TypeSafe's upper limits follow the [TypeSafe API](https://docs.typesafe.ai/api).
Compatible endpoints can impose additional restrictions.

To keep a request portable across both adapters, provide instructions for Choice
and Score and supply at least two choices or score levels. Predicate accepts
instructions, outcome descriptions, or both. Structured descriptions must meet
Jev's native content constraints.

## Use from a Python Action

Obtain a named setup through `RunnerContext`, then supply input and questions for
each direct call. For durable execution and retries, use
[event-driven calls](#event-driven-calls).

Declare Agent resources with `@decision_model_connection` and
`@decision_model_setup`. The decorated method's name becomes the resource name:

```python
from typing import cast

from flink_agents.api.agents.agent import Agent
from flink_agents.api.decision_models import (
    BaseDecisionModelSetup,
    DecisionRequest,
    PredicateQuestion,
)
from flink_agents.api.decorators import (
    action,
    decision_model_connection,
    decision_model_setup,
)
from flink_agents.api.events.event import Event, InputEvent, OutputEvent
from flink_agents.api.resource import ResourceDescriptor, ResourceName, ResourceType
from flink_agents.api.runner_context import RunnerContext


class ReceiptAgent(Agent):
    @decision_model_connection
    @staticmethod
    def decision_server() -> ResourceDescriptor:
        return ResourceDescriptor(clazz=ResourceName.DecisionModel.TYPESAFE_CONNECTION)

    @decision_model_setup
    @staticmethod
    def decision() -> ResourceDescriptor:
        return ResourceDescriptor(
            clazz=ResourceName.DecisionModel.TYPESAFE_SETUP,
            connection="decision_server",
            model="jev-latest",
        )

    @action(InputEvent.EVENT_TYPE)
    @staticmethod
    def classify(event: Event, ctx: RunnerContext) -> None:
        model = cast(
            BaseDecisionModelSetup,
            ctx.get_resource("decision", ResourceType.DECISION_MODEL),
        )
        request = DecisionRequest(
            input=InputEvent.from_event(event).input,
            questions={
                "eligible": PredicateQuestion(
                    instructions="Does the customer have a valid receipt?"
                )
            },
        )
        result = model.decide(request)
        ctx.send_event(OutputEvent(output=result.model_dump(mode="json")))
```

Apply `ReceiptAgent()` to the input stream. `Agent.add_resource()` also
accepts these descriptors; `AgentsExecutionEnvironment.add_resource()` shares
them across Agents, as in the runnable review example. Python-loaded YAML
supports `decision_model_connections` and `decision_model_setups` at the top
level for shared resources and in an Agent declaration:

```yaml
decision_model_connections:
  - name: decision_server
    type: python
    clazz: typesafe
decision_model_setups:
  - name: review_analysis_model
    type: python
    clazz: typesafe
    connection: decision_server
    model: jev-latest
```

Use the `openai` alias for OpenAI connections and setups. These declarations
configure resources; the Action still supplies input and questions for each call.

## Calling from a Java Action

Declare resources on the Java Agent with `@DecisionModelConnection` and
`@DecisionModelSetup`. A Java setup can use a Java or Python connection; the
following Agent uses the built-in Python TypeSafe connection with the common
Java setup:

```java
import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.api.InputEvent;
import org.apache.flink.agents.api.OutputEvent;
import org.apache.flink.agents.api.agents.Agent;
import org.apache.flink.agents.api.annotation.Action;
import org.apache.flink.agents.api.annotation.DecisionModelConnection;
import org.apache.flink.agents.api.annotation.DecisionModelSetup;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.api.decision.BaseDecisionModelSetup;
import org.apache.flink.agents.api.decision.DecisionRequest;
import org.apache.flink.agents.api.decision.DecisionRequest.Question;
import org.apache.flink.agents.api.decision.DecisionResult;
import org.apache.flink.agents.api.resource.PythonResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceName;
import org.apache.flink.agents.api.resource.ResourceType;

import java.util.Map;

public class ReceiptAgent extends Agent {
    @DecisionModelConnection
    public static ResourceDescriptor decisionServer() {
        return PythonResourceDescriptor.Builder.newBuilder(
            ResourceName.DecisionModel.Python.TYPESAFE_CONNECTION)
            .build();
    }

    @DecisionModelSetup
    public static ResourceDescriptor decision() {
        return ResourceDescriptor.Builder.newBuilder(BaseDecisionModelSetup.class.getName())
            .addInitialArgument("connection", "decisionServer")
            .addInitialArgument("model", "jev-latest")
            .build();
    }

    @Action(InputEvent.EVENT_TYPE)
    public static void classify(Event event, RunnerContext ctx) throws Exception {
        BaseDecisionModelSetup model = (BaseDecisionModelSetup)
            ctx.getResource("decision", ResourceType.DECISION_MODEL);
        DecisionRequest request = new DecisionRequest(
            InputEvent.fromEvent(event).getInput(),
            Map.of("eligible", Question.predicate("Does the customer have a valid receipt?")));
        DecisionResult result = model.decide(request,
            Map.of("extra_headers", Map.of("x-request-id", event.getId().toString())));
        ctx.sendEvent(new OutputEvent(result));
    }
}
```

Apply `new ReceiptAgent()` to the input stream. The method names identify the
resources. The runtime resolves the setup's connection and owns the shared
connection's lifecycle; closing a setup does not close it. `Agent.addResource()`
accepts the same descriptors for programmatic registration.

For Python connections, the Python package and the selected provider SDK must be
installed in the job's Python environment. Configure authentication there, as for
a Python Action. Use the OpenAI implementation classes for OpenAI; use the
TypeSafe classes with the Bailian endpoint for Bailian. The same typed question definitions are
converted by the selected adapter. No Java provider SDK is required.

Java questions have concrete `DecisionRequest.PredicateQuestion`,
`ChoiceQuestion`, and `ScoreQuestion` types. The `Question.predicate`,
`Question.choice`, and `Question.score` factories return these types. Construction
validates the common request contract before a provider is called.
`DecisionRequest.PredicateCriteria` expresses the same true/false fields as its
Python counterpart. Choice criteria are a `Map<String, ?>` from choice values to
JSON descriptions; Score criteria are a JSON-valued list of complete level
descriptions. JSON content uses `Object` with recursive JSON validation. The
question constraints and provider conversion rules are the same as in Python.
Choice answers expose a `Map<String, Double>` from choice values to probabilities
through `getProbabilities()`. Score answers expose index-keyed maps through
`getProbabilities()` and `getLegend()`; the legend retains the original criteria
content. Unreported usage and confidence remain null. Refusals are
`DecisionResult.RefusalAnswer` values, while provider failures propagate as
exceptions.

## Event-driven calls

Emit a `DecisionRequestEvent` to use the built-in Decision Action's durable
execution, retries, and execution reports. Its `model` field names a registered
`DECISION_MODEL` resource; `request` is the same `DecisionRequest` used by direct
calls. The following Agent uses the same resource configuration as `ReceiptAgent`
and handles the correlated `DecisionResponseEvent` in a separate Action:

```python
from flink_agents.api.agents.agent import Agent
from flink_agents.api.decision_models import DecisionRequest, PredicateQuestion
from flink_agents.api.decorators import (
    action,
    decision_model_connection,
    decision_model_setup,
)
from flink_agents.api.events.decision_event import (
    DecisionRequestEvent,
    DecisionResponseEvent,
)
from flink_agents.api.events.event import Event, InputEvent, OutputEvent
from flink_agents.api.resource import ResourceDescriptor, ResourceName
from flink_agents.api.runner_context import RunnerContext


class ReceiptEventAgent(Agent):
    @decision_model_connection
    @staticmethod
    def decision_server() -> ResourceDescriptor:
        return ResourceDescriptor(clazz=ResourceName.DecisionModel.TYPESAFE_CONNECTION)

    @decision_model_setup
    @staticmethod
    def decision() -> ResourceDescriptor:
        return ResourceDescriptor(
            clazz=ResourceName.DecisionModel.TYPESAFE_SETUP,
            connection="decision_server",
            model="jev-latest",
        )

    @action(InputEvent.EVENT_TYPE)
    @staticmethod
    def request_decision(event: Event, ctx: RunnerContext) -> None:
        ctx.send_event(
            DecisionRequestEvent(
                model="decision",
                request=DecisionRequest(
                    input=InputEvent.from_event(event).input,
                    questions={
                        "eligible": PredicateQuestion(
                            instructions="Does the customer have a valid receipt?"
                        )
                    },
                ),
                request_params={"extra_headers": {"x-request-id": str(event.id)}},
            )
        )

    @action(DecisionResponseEvent.EVENT_TYPE)
    @staticmethod
    def handle_decision(event: Event, ctx: RunnerContext) -> None:
        response = DecisionResponseEvent.from_event(event)
        # Accessing response on a failed event raises DecisionResponseError.
        ctx.send_event(OutputEvent(output=response.response.model_dump(mode="json")))
```

For Java, emit `new DecisionRequestEvent("decision", request, requestParams)` and
subscribe an Action to `DecisionResponseEvent.EVENT_TYPE`. Read the result with
`DecisionResponseEvent.fromEvent(event).getResponse()`. YAML subscriptions support
the `decision_request` and `decision_response` aliases in both languages.

The response's `request_id` identifies the request event. A successful response
contains the full `DecisionResult`, including any per-question refusals. Provider
exceptions produce a failed response after retries are exhausted; applications
can inspect `is_failed` / `isFailed()` and `error` / `getError()` instead of
accessing the result. Resource lookup failures, cancellation, and failures in
durable state or event delivery propagate to the runtime.

The built-in Action uses the existing `max-retries` (default `0`) and
`retry-wait-interval` (default `1` second) options. Each retry doubles the wait;
the terminal event reports the retry count and total wait. `decision.async`
defaults to `true`; Java calls through Python fall back to synchronous execution
when the runtime does not support asynchronous Python calls.

When an Action State Store is configured, successful and failed provider outcomes
are recorded through the durable execution API, so recovery can replay persisted
outcomes. A provider request whose outcome was not persisted may run again after
recovery. Each attempt emits model execution reports. Retry metrics and available
token counts are recorded under the named model resource; missing token counts
remain unreported. Accumulated retry metrics are also recorded when execution
exits with an exception.

## Run the review analysis example

The runnable
{{< gh_link file="python/flink_agents/examples/decision_models/decision_review_analysis_example.py" name="`decision_review_analysis_example.py`" >}}
uses the same `ProductReview` input as the workflow-agent quickstart. Its
{{< gh_link file="python/flink_agents/examples/quickstart/agents/decision_review_analysis_agent.py" name="`DecisionReviewAnalysisAgent`" >}}
submits three questions through the built-in Decision Action:

| Question | Type | Business use |
|---|---|---|
| `satisfaction` | Score | Five levels from very dissatisfied to very satisfied |
| `primary_reason` | Choice | Main complaint: shipping, quality, fit, other, or none |
| `shipping_damage` | Predicate | Probability that the reviewer reports damage during shipping |

The response Action uses `request_id` to retrieve the original review and emits
the product ID and answers. Adding one to the expected level index produces a
**1–5 satisfaction score that can contain decimals**. A shipping-damage
probability of at least `0.8` triggers `notify_shipping_manager`, which prints a
notification. This threshold is an example business policy. Refusals instead
produce an output containing the product ID and refusal reasons.

Run the Flink job with the selected provider's API key and an accessible model:

```bash
TYPESAFE_API_KEY=... python -m flink_agents.examples.decision_models.decision_review_analysis_example \
    --provider typesafe --model jev-latest
OPENAI_API_KEY=... python -m flink_agents.examples.decision_models.decision_review_analysis_example \
    --provider openai --model gpt-6-luna
```

The job processes three sample reviews and prints their analysis. It requires the
same Flink runtime setup as the quickstart jobs. Use `--base-url` to select another
compatible endpoint. The launcher registers shared resources on
`AgentsExecutionEnvironment`; both providers receive the same request format.

## SDK requirements

The integrations use the official [TypeSafe SDK](https://docs.typesafe.ai/sdk/python)
and [OpenAI Decisions SDK](https://developers.openai.com/api/docs/guides/decisions).
This version requires Pydantic 2.12.x, TypeSafe SDK 0.7.4–0.7.x, and OpenAI SDK
3.26 or later within 3.x.

The OpenAI SDK uses `httpx2` internally and also supports legacy `httpx` clients.
Applications that inject a custom client into `OpenAIChatModelConnection` can
supply either `httpx.Client` or `httpx2.Client`. Setting `timeout=0` disables
timeouts for either client. The default configuration does not require a custom
client.
