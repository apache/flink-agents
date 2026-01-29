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
from pathlib import Path

from pyflink.common import Duration, WatermarkStrategy
from pyflink.datastream import StreamExecutionEnvironment
from pyflink.datastream.connectors.file_system import FileSource, StreamFormat

from flink_agents.api.agents.react_agent import ReActAgent
from flink_agents.api.core_options import AgentExecutionOptions
from flink_agents.api.execution_environment import AgentsExecutionEnvironment
from flink_agents.api.resource import (
    ResourceDescriptor,
    ResourceName,
    ResourceType,
)
from flink_agents.api.tools.tool import Tool
from flink_agents.examples.quickstart.agents.custom_types_and_resources import (
    ProductReview,
    ProductReviewAnalysisRes,
    notify_shipping_manager,
    review_analysis_react_prompt,
)

current_dir = Path(__file__).parent


def main() -> None:
    """Main function for the product shipping question record quickstart example.

    This example demonstrates how to use the Flink Agents to analyze product reviews
    and record shipping questions in a streaming pipeline. The pipeline reads product
    reviews from a file, deserializes each review, and uses an LLM agent to extract
    review scores and unsatisfied reasons. If the unsatisfied reasons are related to
    shipping, the agent will notify the shipping manager. This serves as a minimal,
    end-to-end example of integrating LLM-powered react agent with Flink streaming jobs.
    """
    # Set up the Flink streaming environment and the Agents execution environment.
    env = StreamExecutionEnvironment.get_execution_environment()
    agents_env = AgentsExecutionEnvironment.get_execution_environment(env)

    # limit async request to avoid overwhelming ollama server
    agents_env.get_config().set(AgentExecutionOptions.NUM_ASYNC_THREADS, 2)

    # Add Ollama chat model connection and notify shipping manager tool to be used
    # by the Agent.
    agents_env.add_resource(
        "ollama_server",
        ResourceType.CHAT_MODEL_CONNECTION,
        ResourceDescriptor(clazz=ResourceName.ChatModel.OLLAMA_CONNECTION, request_timeout=120),
    ).add_resource(
        "notify_shipping_manager", ResourceType.TOOL, Tool.from_callable(notify_shipping_manager)
    )

    # Read product reviews from a text file as a streaming source.
    # Each line in the file should be a JSON string representing a ProductReview.
    product_review_stream = env.from_source(
        source=FileSource.for_record_stream_format(
            StreamFormat.text_line_format(),
            f"file:///{current_dir}/resources/",
        )
        .monitor_continuously(Duration.of_minutes(1))
        .build(),
        watermark_strategy=WatermarkStrategy.no_watermarks(),
        source_name="streaming_agent_example",
    ).map(
        lambda x: ProductReview.model_validate_json(
            x
        )  # Deserialize JSON to ProductReview.
    )

    # Create react agent
    review_analysis_react_agent = ReActAgent(
        chat_model=ResourceDescriptor(
            clazz=ResourceName.ChatModel.OLLAMA_SETUP,
            connection="ollama_server",
            model="qwen3:8b",
            tools=["notify_shipping_manager"],
        ),
        prompt=review_analysis_react_prompt,
        output_schema=ProductReviewAnalysisRes,
    )

    # Use the ReAct agent to analyze each product review and notify the shipping manager
    # when needed.
    review_analysis_res_stream = (
        agents_env.from_datastream(
            input=product_review_stream, key_selector=lambda x: x.id
        )
        .apply(review_analysis_react_agent)
        .to_datastream()
    )

    # Print the analysis results to stdout.
    review_analysis_res_stream.print()

    # Execute the Flink pipeline.
    agents_env.execute()


if __name__ == "__main__":
    main()
