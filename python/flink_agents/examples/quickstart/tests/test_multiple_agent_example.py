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
"""Regression tests for score aggregation in the multi-agent example."""

import pytest

from flink_agents.examples.quickstart.agents.custom_types_and_resources import (
    ProductReviewAnalysisRes,
)
from flink_agents.examples.quickstart.workflow_multiple_agent_example import (
    AggregateScoreDistributionAndDislikeReasons,
)


@pytest.mark.parametrize(
    ("scores", "expected"),
    [
        ([0, 6], ["0.0%"] * 5),
        ([1, 5, 0, 6], ["50.0%", "0.0%", "0.0%", "0.0%", "50.0%"]),
        ([3, 3], ["0.0%", "0.0%", "100.0%", "0.0%", "0.0%"]),
    ],
)
def test_score_distribution_ignores_out_of_range_scores(scores, expected):
    reviews = [
        ProductReviewAnalysisRes(id="product", score=score, reasons=[str(score)])
        for score in scores
    ]
    summary, = AggregateScoreDistributionAndDislikeReasons().process(
        "product", None, reviews
    )
    assert summary.score_hist == expected
    assert summary.unsatisfied_reasons == [str(score) for score in scores]
