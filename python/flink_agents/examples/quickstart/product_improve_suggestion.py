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

from pyflink.common import Duration, Time, WatermarkStrategy
from pyflink.datastream import (
    ProcessWindowFunction,
    StreamExecutionEnvironment,
)
from pyflink.datastream.connectors.file_system import FileSource, StreamFormat
from pyflink.datastream.window import TumblingProcessingTimeWindows

from flink_agents.api.execution_environment import AgentsExecutionEnvironment
from flink_agents.examples.quickstart.agents.product_suggestion_agent import (
    ProductReviewSummary,
    ProductSuggestionAgent,
)
from flink_agents.examples.quickstart.agents.review_analysis_agent import (
    ProductReview,
    ProductReviewAnalysisRes,
    ReviewAnalysisAgent,
)
from flink_agents.integrations.chat_models.ollama_chat_model import (
    OllamaChatModelConnection,
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
      1. Reads product reviews from a text file as a streaming source.
      2. Uses an LLM agent to analyze each review and extract score and unsatisfied
         reasons.
      3. Aggregates the analysis results in 1-minute tumbling windows, producing score
         distributions and collecting all unsatisfied reasons.
      4. Uses another LLM agent to generate product improvement suggestions based on the
         aggregated analysis.
      5. Prints the final suggestions to stdout.
    """
    # Set up the Flink streaming environment and the Agents execution environment.
    env = StreamExecutionEnvironment.get_execution_environment()
    agents_env = AgentsExecutionEnvironment.get_execution_environment(env)

    # Add Ollama chat model connection to be used by the ReviewAnalysisAgent
    # and ProductSuggestionAgent.
    agents_env.add_chat_model_connection(
        "ollama_server",
        OllamaChatModelConnection,
        model="qwen3:8b",
        request_timeout=120,
    )

    # TODO: Remove this once https://github.com/apache/flink-agents/issues/173 is fixed.
    # Add required flink-agents jars to the environment.
    env.add_jars(
        f"file:///{current_dir}/../../../../runtime/target/flink-agents-runtime-0.1-SNAPSHOT.jar"
    )
    env.add_jars(
        f"file:///{current_dir}/../../../../plan/target/flink-agents-plan-0.1-SNAPSHOT.jar"
    )
    env.add_jars(
        f"file:///{current_dir}/../../../../api/target/flink-agents-api-0.1-SNAPSHOT.jar"
    )

    # Read product reviews from a text file as a streaming source.
    # Each line in the file should be a JSON string representing a ProductReview.
    product_review_stream = env.from_source(
        source=FileSource.for_record_stream_format(
            StreamFormat.text_line_format(),
            f"file:///{current_dir}/resources/product_review.txt",
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

    # Use the ReviewAnalysisAgent (LLM) to analyze each review.
    # The agent extracts the review score and unsatisfied reasons.
    review_analysis_res_stream = (
        agents_env.from_datastream(
            input=product_review_stream, key_selector=lambda x: x.id
        )
        .apply(ReviewAnalysisAgent())
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
