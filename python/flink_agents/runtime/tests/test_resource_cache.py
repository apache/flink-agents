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
from unittest.mock import Mock

import pytest

from flink_agents.api.resource import ResourceType
from flink_agents.runtime.resource_cache import ResourceCache


def test_closes_resource_when_open_fails() -> None:
    resource = Mock()
    resource.open.side_effect = RuntimeError("open failed")
    provider = Mock()
    provider.provide.return_value = resource
    cache = ResourceCache({ResourceType.CHAT_MODEL: {"model": provider}})

    with pytest.raises(RuntimeError, match="open failed"):
        cache.get_resource("model", ResourceType.CHAT_MODEL)

    resource.close.assert_called_once_with()
    assert cache._cache == {}


def test_continues_closing_after_resource_failure() -> None:
    first = Mock()
    second = Mock()
    second.close.side_effect = RuntimeError("close failed")
    cache = ResourceCache({})
    cache._cache = {ResourceType.CHAT_MODEL: {"first": first, "second": second}}
    cache._resource_context.close = Mock()

    with pytest.raises(RuntimeError, match="close failed"):
        cache.close()

    first.close.assert_called_once_with()
    second.close.assert_called_once_with()
    cache._resource_context.close.assert_called_once_with()
    assert cache._cache == {}
