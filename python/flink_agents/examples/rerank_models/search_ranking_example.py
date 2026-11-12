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
"""Rank questions against text, an image, and a video.

Set ``DASHSCOPE_API_KEY``, then run:

    python -m flink_agents.examples.rerank_models.search_ranking_example

Each question is ranked against the same three results. The job prints the
top result's position and block type.
"""

import sys

from pyflink.common import Configuration
from pyflink.datastream import StreamExecutionEnvironment

from flink_agents.api.execution_environment import AgentsExecutionEnvironment
from flink_agents.api.resource import ResourceDescriptor, ResourceName, ResourceType
from flink_agents.examples.rerank_models.agents.search_ranking_agent import (
    SearchQuestion,
    SearchRankingAgent,
)


def main() -> None:
    """Register the rerank model and rank two questions."""
    config = Configuration()
    config.set_string("python.executable", sys.executable)
    env = StreamExecutionEnvironment.get_execution_environment(config)
    env.set_parallelism(1)
    agents_env = AgentsExecutionEnvironment.get_execution_environment(env)
    agents_env.add_resource(
        "ranker",
        ResourceType.RERANK_MODEL,
        ResourceDescriptor(
            clazz=ResourceName.RerankModel.DASHSCOPE,
            model="qwen3-vl-rerank",
        ),
    )

    questions = env.from_collection(
        [
            SearchQuestion(
                id="definition",
                text="What is a text reranking model?",
            ),
            SearchQuestion(id="picture", text="Which result is a picture?"),
        ]
    )
    output = (
        agents_env.from_datastream(
            input=questions, key_selector=lambda question: question.id
        )
        .apply(SearchRankingAgent())
        .to_datastream()
    )
    output.print()
    agents_env.execute("Rerank Model Search Ranking Example Job")


if __name__ == "__main__":
    main()
