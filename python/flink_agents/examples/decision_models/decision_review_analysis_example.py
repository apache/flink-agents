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
################################################################################
"""Analyze product reviews through the built-in Decision Action.

Set TYPESAFE_API_KEY or OPENAI_API_KEY, then run with a model you can access:

    python -m flink_agents.examples.decision_models.decision_review_analysis_example \
        --provider typesafe --model jev-latest
    python -m flink_agents.examples.decision_models.decision_review_analysis_example \
        --provider openai --model gpt-6-luna
"""

import argparse

from pyflink.datastream import StreamExecutionEnvironment

from flink_agents.api.core_options import AgentExecutionOptions
from flink_agents.api.execution_environment import AgentsExecutionEnvironment
from flink_agents.api.resource import ResourceDescriptor, ResourceName, ResourceType
from flink_agents.examples.quickstart.agents.custom_types_and_resources import (
    ProductReview,
)
from flink_agents.examples.quickstart.agents.decision_review_analysis_agent import (
    DecisionReviewAnalysisAgent,
)


def main() -> None:
    """Register decision resources and run review analysis in a Flink pipeline."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--provider", choices=["typesafe", "openai"], required=True)
    parser.add_argument(
        "--model", required=True, help="A model available to your account."
    )
    parser.add_argument("--base-url", help="Optional provider API root.")
    args = parser.parse_args()

    env = StreamExecutionEnvironment.get_execution_environment()
    env.set_parallelism(1)
    agents_env = AgentsExecutionEnvironment.get_execution_environment(env)
    agents_env.get_config().set(AgentExecutionOptions.NUM_ASYNC_THREADS, 2)

    if args.provider == "typesafe":
        connection_class = ResourceName.DecisionModel.TYPESAFE_CONNECTION
        setup_class = ResourceName.DecisionModel.TYPESAFE_SETUP
    else:
        connection_class = ResourceName.DecisionModel.OPENAI_CONNECTION
        setup_class = ResourceName.DecisionModel.OPENAI_SETUP
    options = {"base_url": args.base_url} if args.base_url else {}
    agents_env.add_resource(
        "decision_server",
        ResourceType.DECISION_MODEL_CONNECTION,
        ResourceDescriptor(clazz=connection_class, **options),
    )
    agents_env.add_resource(
        "review_analysis_model",
        ResourceType.DECISION_MODEL,
        ResourceDescriptor(
            clazz=setup_class, connection="decision_server", model=args.model
        ),
    )

    product_review_stream = env.from_collection(
        [
            ProductReview(id="shoes-1", review="Comfortable shoes. I love them!"),
            ProductReview(
                id="book-1",
                review="The package arrived crushed and the book inside was torn.",
            ),
            ProductReview(
                id="headphones-1",
                review="The headphones stopped working after one week. Poor quality.",
            ),
        ]
    )
    review_analysis_res_stream = (
        agents_env.from_datastream(
            input=product_review_stream, key_selector=lambda review: review.id
        )
        .apply(DecisionReviewAnalysisAgent())
        .to_datastream()
    )
    review_analysis_res_stream.print()
    agents_env.execute("Decision Model Review Analysis Example Job")


if __name__ == "__main__":
    main()
