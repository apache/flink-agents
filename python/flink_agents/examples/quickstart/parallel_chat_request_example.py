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

from flink_agents.api.execution_environment import AgentsExecutionEnvironment
from flink_agents.api.resource import ResourceDescriptor, ResourceName, ResourceType
from flink_agents.examples.quickstart.agents.parallel_chat_agent import (
    INPUT_TEXT,
    ParallelChatAgent,
)


def main() -> None:
    """Main function for the parallel chat request quickstart example.

    This example demonstrates how to use Flink Agents to analyze a restaurant
    review by fanning out multiple parallel LLM calls — one per sentiment
    dimension — and aggregating the results with a final LLM call. This serves
    as a minimal, end-to-end example of integrating parallel LLM-powered agents
    with Flink streaming jobs.
    """
    # Set up the Flink streaming environment and the Agents execution environment.
    env = StreamExecutionEnvironment.get_execution_environment()
    env.set_parallelism(1)
    agents_env = AgentsExecutionEnvironment.get_execution_environment(env)

    # Add Ollama chat model connection to be used by the ParallelChatAgent.
    agents_env.add_resource(
        "ollama_server",
        ResourceType.CHAT_MODEL_CONNECTION,
        ResourceDescriptor(
            clazz=ResourceName.ChatModel.OLLAMA_CONNECTION,
            request_timeout=240.0,
        ),
    )

    # Create input stream with a single restaurant review.
    input_stream = env.from_collection(
        collection=[{"id": 1, "text": INPUT_TEXT}],
    )

    # Use the ParallelChatAgent to analyze the review with parallel LLM calls.
    output_stream = (
        agents_env.from_datastream(
            input=input_stream, key_selector=lambda x: x["id"]
        )
        .apply(ParallelChatAgent())
        .to_datastream()
    )

    # Print the analysis results to stdout.
    output_stream.print()

    # Execute the Flink pipeline.
    agents_env.execute()


if __name__ == "__main__":
    main()
