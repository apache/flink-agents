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

Decision models have a Python API and Python YAML declarations. Java Actions can
call the same Python providers through the DecisionModel cross-language API.

## Direct Python call

The following example uses TypeSafe's Jev model. Set `TYPESAFE_API_KEY` in the
environment before running it.

```python
from flink_agents.api.decision_models import (
    ChoiceAnswer,
    ChoiceQuestion,
    DecisionRequest,
    RefusalAnswer,
)
from flink_agents.integrations.decision_models.typesafe_decision_model import (
    TypeSafeDecisionModelConnection,
    TypeSafeDecisionModelSetup,
)

connection = TypeSafeDecisionModelConnection()
model = TypeSafeDecisionModelSetup(connection=connection, model="jev-latest")

try:
    result = model.decide(
        DecisionRequest(
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
    )
    answer = result.answers["team"]
    if isinstance(answer, RefusalAnswer):
        print("Send to manual review")
    elif isinstance(answer, ChoiceAnswer):
        print(answer.choice)
        for entry in answer.probabilities:
            print(entry.value, entry.probability)
    print(result.usage)
finally:
    connection.close()
```

A connection owns the SDK client and can serve multiple model setups. For direct
calls, close the connection when finished. Named connections registered with FA
are opened and closed by the resource lifecycle; an Action should not close them.

To use OpenAI, select its connection and setup and supply its native Choice
criteria format. The adapter preserves request content; it does not convert a
TypeSafe criteria map into an OpenAI choices array:

```python
from flink_agents.integrations.decision_models.openai_decision_model import (
    OpenAIDecisionModelConnection,
    OpenAIDecisionModelSetup,
)

# Reads OPENAI_API_KEY; OPENAI_BASE_URL is also supported by the SDK.
connection = OpenAIDecisionModelConnection()
model = OpenAIDecisionModelSetup(connection=connection, model="gpt-6-luna")
request = DecisionRequest(
    input="I was charged twice for my order.",
    questions={
        "team": ChoiceQuestion(
            instructions="Which team should handle this ticket?",
            criteria=[
                {"value": "billing", "description": "Payments, invoices, and refunds."},
                {"value": "technical", "description": "Problems using the product."},
                {"value": "other"},
            ],
        )
    },
)
try:
    result = model.decide(request)
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
model = TypeSafeDecisionModelSetup(
    connection=connection,
    model="decision-model-preview",
)
```

The SDK appends `/v1/systemone` to the TypeSafe API root. Choose a model and
regional endpoint available to your account. The Bailian example follows its
[standard Decision API documentation](https://help.aliyun.com/zh/model-studio/decision-model-api);
other gateway or fine-tuned deployment authentication must be checked separately.

## Questions and results

Questions are supplied to each `decide()` call rather than stored in the setup.
A single request can contain several independent questions sharing the same input.
Question identifiers and string choice values are case-sensitive. Each question
produces one answer under the same identifier; an incomplete or inconsistent
response raises an error.

| Question | Definition | Answer |
|---|---|---|
| `PredicateQuestion` | Instructions and optional true/false criteria | `PredicateAnswer.probability_true` |
| `ChoiceQuestion` | Provider-native Choice `criteria` and optional instructions | `ChoiceAnswer.choice`, full list of `ChoiceProbability` entries |
| `ScoreQuestion` | Ordered level descriptions in `criteria` and optional instructions | `ScoreAnswer.score`, full list of `ScoreProbability` entries |

Each `ChoiceProbability` contains a `value` (`str` or `bool`) and a `probability`
(`float`). `ChoiceAnswer.choice` preserves the selected value's type. For example,
`True` and `"true"` are distinct choices and can occur in the same distribution.
Choice probability entries retain the provider's order.

A score is the expected **zero-based level index** and can fall between levels.
Level descriptions need not be unique; their positions identify them.
Pass levels as a list of strings, dictionaries, or lists, choosing the format
supported by the provider below.

Each `ScoreProbability` contains a non-negative integer `value` (the input level
index), a required `label` (string, dictionary, or list), and a `probability`
(`float`). TypeSafe's `legend["i"]` and `probabilities["i"]` become one entry with
`value=i`, `label=legend["i"]`, and `probability=probabilities["i"]`. OpenAI's
`value`, `label`, and `probability` fields are preserved directly. Score entries
are ordered by ascending level index, even if the provider returns them in a
different order.

Read the returned level content from `entry.label`, including structured labels:

```python
from flink_agents.api.decision_models import ScoreAnswer


def print_score(answer: ScoreAnswer) -> None:
    print(answer.score)
    for entry in answer.probabilities:
        print(entry.value, entry.label, entry.probability)
```

Choice and score answers retain `provider_confidence` when reported. This is the
provider's own measure, not a replacement for the full probability distribution.

`RefusalAnswer` represents an explicit per-question provider refusal. OpenAI
supports this answer type. Jev currently documents only Noul, Choice, and Score;
an undocumented Jev answer type raises an error. Authentication failures,
timeouts, and other request failures raise exceptions rather than becoming
refusals or low-confidence answers.

`DecisionResult.usage` applies to the whole request. Unreported counts remain
`None`; they are not filled with zero or inferred from other counts.
`model` contains the provider-reported model when available, and `metadata`
retains additional response-level and usage fields, such as request IDs, latency,
and token usage details. There is no required response identifier.

### Provider request formats

`DecisionInput` is `str | Dict[str, Any] | List[Any]`, used for `input` and question
content. `PredicateQuestion.instructions` is required; Choice and Score
instructions can be omitted or set to `None`. `ChoiceQuestion.criteria` accepts a
dictionary or a list of dictionaries, and `ScoreQuestion.criteria` accepts a list.
The request model imposes no minimum number of questions, choices, or levels.
The SDK and service determine supported content and counts, including whether
container values can be serialized; these types do not guarantee support for
arbitrary Python objects.

Choose content that matches the provider's native format, as described in the
[TypeSafe API reference](https://docs.typesafe.ai/api) and
[OpenAI API reference](https://developers.openai.com/api/reference/resources/decisions/methods/create):

| FA field | TypeSafe / Jev | OpenAI |
|---|---|---|
| `input` | String, object, or array as `state` | String or native user-message list |
| `instructions` | String, object, or array; the SDK permits omission for Choice and Score | Supply text instructions for every question |
| Predicate `criteria` | Optional `true`/`false` descriptions, each string, object, array, or null | Omitted, as defined by the FA Predicate contract |
| Choice `criteria` | Map from string choice IDs to string, object, array, or null descriptions | List of objects with string or boolean `value` and optional `description`, passed as `choices` |
| Score `criteria` | Ordered strings, objects, or arrays | Ordered objects with `label` and optional `description`, passed as `levels` |

The adapters pass field content to the SDK without JSON-encoding input or
instructions. OpenAI Choice and Score only rename `criteria` to `choices` and
`levels`; maps and lists are not converted into one another, and descriptions
are not added or rewritten. OpenAI ignores Predicate criteria, so instructions
must contain the complete condition. TypeSafe receives Predicate criteria
unchanged, and the answer always estimates the probability of true.

FA adds no provider-specific request validation. The SDK or service determines
whether the supplied content is accepted; unsupported formats may be rejected
only by the service. SDK acceptance does not establish service support for omitted
instructions or single-entry criteria. Additional fields are not assigned
portable semantics.

## Review analysis Agent

The runnable
[`decision_review_analysis_example.py`](https://github.com/apache/flink-agents/blob/main/python/flink_agents/examples/quickstart/decision_review_analysis_example.py)
uses the same `ProductReview` input as the workflow-agent quickstart. Its
[`DecisionReviewAnalysisAgent`](https://github.com/apache/flink-agents/blob/main/python/flink_agents/examples/quickstart/agents/decision_review_analysis_agent.py)
processes each `InputEvent` in an Action, retrieves a named decision resource, and
asks three questions in one call:

| Question | Type | Business use |
|---|---|---|
| `satisfaction` | Score | Five levels from very dissatisfied to very satisfied |
| `primary_reason` | Choice | Main complaint: shipping, quality, fit, other, or none |
| `shipping_damage` | Predicate | Probability that the reviewer reports damage during shipping |

The Action emits the product ID, satisfaction score, primary reason, and shipping
probability. It adds one to the zero-based expected score to report a **1–5 score
that can contain decimals**. The primary reason is a predefined category, not a
free-text explanation. The output is a dictionary; it does not use the chat
example's integer-valued `ProductReviewAnalysisRes`.

When the shipping-damage probability is at least `0.8`, the Action calls the
quickstart's `notify_shipping_manager` function, which prints a notification.
This threshold is an example business policy. If any question returns a refusal,
the Action instead emits the product ID and refusals without notifying. Request
failures propagate from the Action.

Run the Flink job with the selected provider's API key and an accessible model:

```bash
TYPESAFE_API_KEY=... python -m flink_agents.examples.quickstart.decision_review_analysis_example \
    --provider typesafe --model jev-latest
OPENAI_API_KEY=... python -m flink_agents.examples.quickstart.decision_review_analysis_example \
    --provider openai --model gpt-6-luna
```

The job processes three sample reviews and prints their analysis. It requires the
same Flink runtime setup as the other quickstart jobs. Use `--base-url` to select
another compatible endpoint. The launcher registers the connection and model
setup on `AgentsExecutionEnvironment`; FA owns their lifecycle.

## Use from a Python Action

Obtain a named setup through `RunnerContext`, then supply input and questions for
each call. For example, the review Agent uses:

```python
from typing import cast

from flink_agents.api.decision_models import BaseDecisionModelSetup
from flink_agents.api.resource import ResourceType

model = cast(
    BaseDecisionModelSetup,
    ctx.get_resource("review_analysis_model", ResourceType.DECISION_MODEL),
)
result = model.decide(request)
```

Register resources with `@decision_model_connection` and `@decision_model_setup`,
or share them across Agents through `AgentsExecutionEnvironment.add_resource()`.
For the TypeSafe version of the review example:

```python
from flink_agents.api.resource import ResourceDescriptor, ResourceName, ResourceType

agents_env.add_resource(
    "decision_server",
    ResourceType.DECISION_MODEL_CONNECTION,
    ResourceDescriptor(clazz=ResourceName.DecisionModel.TYPESAFE_CONNECTION),
)
agents_env.add_resource(
    "review_analysis_model",
    ResourceType.DECISION_MODEL,
    ResourceDescriptor(
        clazz=ResourceName.DecisionModel.TYPESAFE_SETUP,
        connection="decision_server",
        model="jev-latest",
    ),
)
```

The setup can also be registered with `Agent.add_resource()`. Python-loaded YAML
supports `decision_model_connections` and `decision_model_setups` both at the
top level for shared resources and in an Agent declaration:

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

Register the Python connection and setup on the Java Agent. The runtime owns
their lifecycle, and multiple setups can share one connection:

```java
import org.apache.flink.agents.api.decision.BaseDecisionModelSetup;
import org.apache.flink.agents.api.decision.DecisionRequest;
import org.apache.flink.agents.api.decision.DecisionRequest.Question;
import org.apache.flink.agents.api.decision.DecisionResult;
import org.apache.flink.agents.api.resource.PythonResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceType;

import java.util.Map;

agent.addResource("decision_server", ResourceType.DECISION_MODEL_CONNECTION,
    PythonResourceDescriptor.Builder.newBuilder(
        "flink_agents.integrations.decision_models.typesafe_decision_model.TypeSafeDecisionModelConnection")
        .build());
agent.addResource("decision", ResourceType.DECISION_MODEL,
    PythonResourceDescriptor.Builder.newBuilder(
        "flink_agents.integrations.decision_models.typesafe_decision_model.TypeSafeDecisionModelSetup")
        .addInitialArgument("connection", "decision_server")
        .addInitialArgument("model", "jev-latest")
        .build());

// Inside the Java Action, using its RunnerContext:
BaseDecisionModelSetup model = (BaseDecisionModelSetup)
    ctx.getResource("decision", ResourceType.DECISION_MODEL);
DecisionResult result = model.decide(new DecisionRequest(
    "The customer has a valid receipt.",
    Map.of("eligible", Question.predicate("Does the customer have a receipt?"))));
DecisionResult.Answer answer = result.getAnswers().get("eligible");
if (answer instanceof DecisionResult.PredicateAnswer) {
    double probability = ((DecisionResult.PredicateAnswer) answer).getProbabilityTrue();
    // Apply the application's threshold here.
}
```

The Python package and the selected provider SDK must be installed in the job's
Python environment. Configure authentication there, as for a Python Action.
Use the OpenAI implementation classes and native criteria for OpenAI; use the
TypeSafe classes with the Bailian endpoint for Bailian. No Java provider SDK is
required.

`Question.choice(criteria, instructions)` and `Question.score(levels, instructions)`
accept null instructions when the provider supports omission. Structured input,
instructions and labels retain their JSON shape. Choice values remain `String`
or `Boolean`, including in the probability list; unreported usage and confidence
remain null. Refusals are `DecisionResult.RefusalAnswer` values, while provider
failures propagate as exceptions. An Action must not close framework-owned
resources.

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
