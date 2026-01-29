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
from typing import Iterable

from pyflink.common import Configuration, Time
from pyflink.datastream import (
    ProcessWindowFunction,
    StreamExecutionEnvironment,
)
from pyflink.datastream.window import TumblingProcessingTimeWindows
from pyflink.table import DataTypes, Schema, StreamTableEnvironment, TableDescriptor
from pyflink.table.expressions import col

from flink_agents.api.execution_environment import AgentsExecutionEnvironment
from flink_agents.api.resource import ResourceType
from flink_agents.examples.quickstart.agents.custom_types_and_resources import (
    ProductReviewSummary,
    ollama_server_descriptor,
)
from flink_agents.examples.quickstart.agents.product_suggestion_agent import (
    ProductSuggestionAgent,
)
from flink_agents.examples.quickstart.agents.review_analysis_agent import (
    ProductReviewAnalysisRes,
)
from flink_agents.examples.quickstart.agents.table_review_analysis_agent import (
    TableKeySelector,
    TableReviewAnalysisAgent,
)

current_dir = Path(__file__).parent


class AggregateScoreDistributionAndDislikeReasons(ProcessWindowFunction):
    """Aggregate score distribution and dislike reasons."""

    def process(
        self,
        key: str,
        context: "ProcessWindowFunction.Context",
        elements: Iterable[ProductReviewAnalysisRes],
    ) -> Iterable[ProductReviewSummary]:
        """Aggregate score distribution and dislike reasons."""
        rating_counts = [0 for _ in range(5)]
        reason_list = []
        for element in elements:
            rating = element.score
            if 1 <= rating <= 5:
                rating_counts[rating - 1] += 1
            reason_list = reason_list + element.reasons
        total = sum(rating_counts)
        percentages = [round((x / total) * 100, 1) for x in rating_counts]
        formatted_percentages = [f"{p}%" for p in percentages]
        return [
            ProductReviewSummary(
                id=key,
                score_hist=formatted_percentages,
                unsatisfied_reasons=reason_list,
            )
        ]


def main() -> None:
    """Main function for the product improvement suggestion quickstart example.

    This example demonstrates a multi-stage streaming pipeline using Flink Agents:
      1. Reads product reviews from a JSON file using Flink Table API.
      2. Uses an LLM agent to analyze each review and extract score and unsatisfied
         reasons.
      3. Aggregates the analysis results in 1-minute tumbling windows, producing score
         distributions and collecting all unsatisfied reasons.
      4. Uses another LLM agent to generate product improvement suggestions based on the
         aggregated analysis.
      5. Prints the final suggestions to stdout.
    """
    # Set up the Flink streaming environment and the Agents execution environment.
    config = Configuration()
    config.set_string("pipeline.auto-watermark-interval", "1ms")
    config.set_string("python.fn-execution.bundle.size", "1")
    env = StreamExecutionEnvironment.get_execution_environment(config)
    env.set_parallelism(1)

    # Create StreamTableEnvironment for Table API support.
    t_env = StreamTableEnvironment.create(stream_execution_environment=env)

    # Create AgentsExecutionEnvironment with both env and t_env to enable
    # Table API integration.
    agents_env = AgentsExecutionEnvironment.get_execution_environment(
        env=env, t_env=t_env
    )

    # Add Ollama chat model connection to be used by the ReviewAnalysisAgent
    # and ProductSuggestionAgent.
    agents_env.add_resource(
        "ollama_server",
        ResourceType.CHAT_MODEL_CONNECTION,
        ollama_server_descriptor,
    )

    # Read product reviews from a JSON file using Flink Table API.
    # Each line in the file should be a JSON string representing a ProductReview.
    # Define the source table with watermark based on the 'ts' column.
    t_env.create_temporary_table(
        "product_reviews",
        TableDescriptor.for_connector("filesystem")
        .schema(
            Schema.new_builder()
            .column("id", DataTypes.STRING())
            .column("review", DataTypes.STRING())
            .column("ts", DataTypes.BIGINT())
            .column_by_expression("rowtime", "TO_TIMESTAMP_LTZ(`ts` * 1000, 3)")
            .watermark("rowtime", "rowtime - INTERVAL '0' SECOND")
            .build()
        )
        .option("format", "json")
        .option("path", f"file:///{current_dir}/resources/product_review.txt")
        .build(),
    )

    input_table = t_env.from_path("product_reviews").select(
        col("id"), col("review"), col("ts")
    )

    # Use the TableReviewAnalysisAgent (LLM) to analyze each review.
    # The agent extracts the review score and unsatisfied reasons.
    review_analysis_res_stream = (
        agents_env.from_table(input=input_table, key_selector=TableKeySelector())
        .apply(TableReviewAnalysisAgent())
        .to_datastream()
    )

    # Aggregate the analysis results in 1-minute tumbling windows.
    # This produces a score distribution and collects all unsatisfied reasons for each
    # product.
    aggregated_analysis_res_stream = (
        review_analysis_res_stream.key_by(lambda x: x.id)
        .window(TumblingProcessingTimeWindows.of(Time.minutes(1)))
        .process(AggregateScoreDistributionAndDislikeReasons())
    )

    # Use the ProductSuggestionAgent (LLM) to generate product improvement suggestions
    # based on the aggregated analysis results.
    product_suggestion_res_stream = (
        agents_env.from_datastream(
            input=aggregated_analysis_res_stream,
            key_selector=lambda x: x.id,
        )
        .apply(ProductSuggestionAgent())
        .to_datastream()
    )

    # Print the final product improvement suggestions to stdout.
    product_suggestion_res_stream.print()

    # Execute the pipeline.
    agents_env.execute()


if __name__ == "__main__":
    main()
