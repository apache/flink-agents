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
from typing import Any

import pytest

from flink_agents.runtime.java.java_chat_model import (
    JavaChatModelConnectionImpl,
    JavaChatModelSetupImpl,
)
from flink_agents.runtime.java.java_embedding_model import (
    JavaEmbeddingModelConnectionImpl,
    JavaEmbeddingModelSetupImpl,
)
from flink_agents.runtime.java.java_resource_wrapper import (
    set_java_resource_metric_group,
)


class _JavaResource:
    def __init__(self) -> None:
        self.metric_group: Any = None

    def setMetricGroup(self, metric_group: Any) -> None:
        self.metric_group = metric_group


class _MetricGroup:
    def __init__(self) -> None:
        self._j_metric_group = object()


@pytest.mark.parametrize(
    "resource",
    [
        JavaChatModelConnectionImpl(
            j_resource=_JavaResource(), j_resource_adapter=None
        ),
        JavaChatModelSetupImpl(
            j_resource=_JavaResource(),
            j_resource_adapter=None,
            connection="connection",
            model="model",
        ),
        JavaEmbeddingModelConnectionImpl(
            j_resource=_JavaResource(), j_resource_adapter=None
        ),
        JavaEmbeddingModelSetupImpl(
            j_resource=_JavaResource(),
            j_resource_adapter=None,
            connection="connection",
            model="model",
        ),
    ],
)
def test_java_resource_wrappers_forward_metric_group(resource):
    metric_group = _MetricGroup()

    resource.set_metric_group(metric_group)

    assert resource.metric_group is metric_group
    assert resource._j_resource.metric_group is metric_group._j_metric_group


def test_set_java_resource_metric_group_unwraps_flink_metric_group():
    java_resource = _JavaResource()
    metric_group = _MetricGroup()

    set_java_resource_metric_group(java_resource, metric_group)

    assert java_resource.metric_group is metric_group._j_metric_group
