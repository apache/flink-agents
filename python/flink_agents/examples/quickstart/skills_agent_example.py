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
from pyflink.datastream import StreamExecutionEnvironment

from flink_agents.api.core_options import AgentExecutionOptions
from flink_agents.api.execution_environment import AgentsExecutionEnvironment
from flink_agents.api.resource import ResourceType
from flink_agents.examples.quickstart.agents.custom_types_and_resources import (
    ollama_server_descriptor,
)
from flink_agents.examples.quickstart.agents.math_agent import MathAgent


def main() -> None:
    """Main function for the agent skills quickstart example.

    This example demonstrates how to use Agent Skills with Flink Agents. A stream
    of arithmetic questions is processed by ``MathAgent``, whose chat model has a
    ``math-calculator`` skill enabled. For each question the model loads the skill
    and follows its instructions to compute the answer with the ``bc`` calculator,
    then the result is printed to stdout. This serves as a minimal, end-to-end
    example of integrating skill-powered agents with Flink streaming jobs.
    """
    # Set up the Flink streaming environment and the Agents execution environment.
    env = StreamExecutionEnvironment.get_execution_environment()
    agents_env = AgentsExecutionEnvironment.get_execution_environment(env)

    # limit async request to avoid overwhelming ollama server
    agents_env.get_config().set(AgentExecutionOptions.NUM_ASYNC_THREADS, 2)

    # Add Ollama chat model connection to be used by the MathAgent.
    agents_env.add_resource(
        "ollama_server",
        ResourceType.CHAT_MODEL_CONNECTION,
        ollama_server_descriptor,
    )

    # A small stream of arithmetic questions to answer.
    question_stream = env.from_collection(
        [
            "What is (2 + 3) * 4?",
            "Compute 2 ^ 10.",
            "What is 144 divided by 12?",
        ],
    )

    # Use the MathAgent to answer each question with the math-calculator skill.
    answer_stream = (
        agents_env.from_datastream(input=question_stream, key_selector=lambda x: x)
        .apply(MathAgent())
        .to_datastream()
    )

    # Print the answers to stdout.
    answer_stream.print()

    # Execute the Flink pipeline.
    agents_env.execute()


if __name__ == "__main__":
    main()
