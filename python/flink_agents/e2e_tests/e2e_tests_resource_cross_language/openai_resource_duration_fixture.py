################################################################################
#  Licensed to the Apache Software Foundation (ASF) under one
#  or more contributor license agreements.  See the NOTICE file
#  distributed with this work for additional information
#  regarding copyright ownership.  The ASF licenses this file
#  to you under the Apache License, Version 2.0 (the
#  "License"); you may not use this file except in compliance
#  with the License.  You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
#  Unless required by applicable law or agreed to in writing, software
#  distributed under the License is distributed on an "AS IS" BASIS,
#  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
#  See the License for the specific language governing permissions and
# limitations under the License.
#################################################################################
"""Generate real Python plan JSON for Java resource duration regression tests."""

import json
import sys
from pathlib import Path

import yaml

from flink_agents.api.resource import JavaResourceDescriptor, ResourceType
from flink_agents.api.yaml.loader import build_agents
from flink_agents.plan.agent_plan import AgentPlan
from flink_agents.plan.configuration import AgentConfiguration
from flink_agents.plan.resource_provider import JavaResourceProvider


def write_cases(directory: Path, entry: str) -> None:
    """Exercise direct declarations or the YAML loader, then the plan serializer."""
    cases = []
    values = [
        ({}, 60_000),
        ({"timeout": "1500 us"}, 2),
        ({"timeout": "PT0.0015S"}, 2),
        ({"timeout": 120}, 120),
        ({"timeout": "120"}, 120),
        ({"timeout": "1500001 ns"}, None),
        ({"timeout": "PT0.001500001S"}, None),
    ]
    for clazz in (
        "OpenAICompletionsConnection",
        "VLLMChatModelConnection",
        "AzureOpenAIChatModelConnection",
        "OpenAIResponsesModelConnection",
        "OpenAIEmbeddingModelConnection",
    ):
        embedding = clazz == "OpenAIEmbeddingModelConnection"
        module = "embeddingmodels" if embedding else "chatmodels"
        fqn = f"org.apache.flink.agents.integrations.{module}.openai.{clazz}"
        resource_type = (
            ResourceType.EMBEDDING_MODEL_CONNECTION
            if embedding
            else ResourceType.CHAT_MODEL_CONNECTION
        )
        parameter = "request_timeout" if embedding else "timeout"
        section = (
            "embedding_model_connections" if embedding else "chat_model_connections"
        )
        for arguments, expected in values:
            if embedding and expected == 60_000:
                expected = 30_000
            arguments = {parameter: arguments["timeout"]} if arguments else {}
            arguments = {"api_key": "test-key", **arguments}
            if clazz == "AzureOpenAIChatModelConnection":
                arguments.update(
                    api_version="2024-02-01",
                    azure_endpoint="https://example.openai.azure.com",
                )
            if entry == "yaml":
                path = directory / "agent.yaml"
                path.write_text(
                    yaml.safe_dump(
                        {
                            "agents": [
                                {
                                    "name": "duration-test",
                                    section: [
                                        {
                                            "name": "connection",
                                            "clazz": fqn,
                                            "type": "java",
                                            **arguments,
                                        }
                                    ],
                                }
                            ]
                        }
                    )
                )
                agents, _, _ = build_agents(path)
                plan = AgentPlan.from_agent(
                    agents["duration-test"], AgentConfiguration()
                )
            else:
                descriptor = JavaResourceDescriptor(clazz=fqn, **arguments)
                provider = JavaResourceProvider.get(
                    "connection", descriptor, resource_type
                )
                plan = AgentPlan(
                    actions={},
                    config=AgentConfiguration(),
                    resource_providers={
                        resource_type: {
                            "connection": provider,
                        }
                    },
                )
            cases.append(
                {
                    "name": f"{entry}/{clazz}/{arguments.get(parameter, 'default')}",
                    "expected_millis": expected,
                    "embedding": embedding,
                    "plan": json.loads(plan.model_dump_json()),
                }
            )
    (directory / "cases.json").write_text(json.dumps(cases))


if __name__ == "__main__":
    write_cases(Path(sys.argv[1]), sys.argv[2])
