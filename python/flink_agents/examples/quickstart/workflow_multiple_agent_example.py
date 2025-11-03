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
from datetime import datetime

from pyflink.common import Duration, Time, WatermarkStrategy
from pyflink.datastream import StreamExecutionEnvironment
from pyflink.datastream.functions import (
    ProcessAllWindowFunction,
    ProcessWindowFunction,
)
from pyflink.datastream.connectors.file_system import FileSource, StreamFormat
from pyflink.datastream.window import TumblingProcessingTimeWindows

from flink_agents.api.execution_environment import AgentsExecutionEnvironment
from flink_agents.examples.quickstart.agents.alert_types_and_resources import (
    ollama_server_descriptor,
    AlertInfoSummary,
    AlertInfo,
)
from flink_agents.examples.quickstart.agents.alert_suggestion_agent import (
    AlertSuggestionAgent,
    FinalAlertSuggestionAgent,
)
from flink_agents.examples.quickstart.agents.info_analysis_agent import (
    AlertInfoAnalysisRes,
    InfoAnalysisAgent,
)

current_dir = Path(__file__).parent


class AggregateAlertRootCausesAndReasonsAll(ProcessAllWindowFunction):
    """Aggregate alert root causes and reasons globally (no key)."""

    def process(
        self,
        context: "ProcessAllWindowFunction.Context",
        elements: Iterable[AlertInfoAnalysisRes],
    ) -> Iterable[AlertInfoSummary]:
        id_list = []
        reason_list = []
        for element in elements:
            id_list.append(element.id)
            reason_list.append(element.reasons)
        # 动态生成时间戳作为id，格式：yy-mm-dd-HH:MM
        timestamp_str = datetime.now().strftime("%y-%m-%d-%H:%M")
        return [
            AlertInfoSummary(
                timestamp=timestamp_str,
                id_list=id_list,
                alert_reasons=reason_list,
            )
        ]


def main() -> None:
    """Main function for the Alert improvement suggestion quickstart example.

    This example demonstrates a multi-stage streaming pipeline using Flink Agents:
      1. Reads Alert infos from a text file as a streaming source.
      2. Uses an LLM agent to analyze each info and extract score and unsatisfied
         reasons.
      3. Aggregates the analysis results in 1-minute tumbling windows, producing score
         distributions and collecting all unsatisfied reasons.
      4. Uses another LLM agent to generate Alert improvement suggestions based on the
         aggregated analysis.
      5. Prints the final suggestions to stdout.
    """
    # Set up the Flink streaming environment and the Agents execution environment.
    env = StreamExecutionEnvironment.get_execution_environment()
    env.set_parallelism(3)  # 显式设置默认并行度为 1
    agents_env = AgentsExecutionEnvironment.get_execution_environment(env)

    # Add Ollama chat model connection to be used by the InfoAnalysisAgent
    # and AlertSuggestionAgent.
    agents_env.add_resource(
        "ollama_server",
        ollama_server_descriptor,
    )

    # Read Alert infos from a text file as a streaming source.
    # Each line in the file should be a JSON string representing a AlertInfo.
    alert_info_stream = env.from_source(
        source=FileSource.for_record_stream_format(
            StreamFormat.text_line_format(),
            f"file:///{current_dir}/resources/",
        )
        .monitor_continuously(Duration.of_minutes(1))
        .build(),
        watermark_strategy=WatermarkStrategy.no_watermarks(),
        source_name="streaming_agent_example",
    ).map(
        lambda x: AlertInfo.model_validate_json(
            x
        )  # Deserialize JSON to AlertInfo.
    )

    # Use the InfoAnalysisAgent (LLM) to analyze each info.
    # The agent extracts the info score and unsatisfied reasons.
    info_analysis_res_stream = agents_env.from_datastream(
            input=alert_info_stream, key_selector=lambda x: x.id
        ).apply(InfoAnalysisAgent()).to_datastream()
    

    # Aggregate the analysis results in 1-minute tumbling windows.
    # This produces a score distribution and collects all unsatisfied reasons for each
    # product.
    aggregated_analysis_res_stream = (
        info_analysis_res_stream
        .window_all(TumblingProcessingTimeWindows.of(Time.minutes(1)))
        .process(AggregateAlertRootCausesAndReasonsAll())
    )

    # Use the AlertSuggestionAgent (LLM) to generate Alert improvement suggestions
    # based on the aggregated analysis results.
    Alert_suggestion_res_stream = (
        agents_env.from_datastream(
            input=aggregated_analysis_res_stream,
            key_selector=lambda x: x.timestamp,
        )
        .apply(AlertSuggestionAgent())
        .to_datastream()
    )
    
    Final_Alert_suggestion_res_stream = (
        agents_env.from_datastream(
            input=Alert_suggestion_res_stream,
            key_selector=lambda x: x.timestamp,
        )
        .apply(FinalAlertSuggestionAgent())
        .to_datastream()
    )

    # Print the final Alert improvement suggestions to stdout.
    Final_Alert_suggestion_res_stream.print()

    # Execute the pipeline.
    agents_env.execute()


if __name__ == "__main__":
    main()
