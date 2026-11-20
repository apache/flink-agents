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
import pytest

from flink_agents.integrations.chat_models.anthropic.anthropic_chat_model import (
    AnthropicChatModelConnection,
)


@pytest.mark.parametrize(("timeout", "expected"), [(0, None), (1.5, 1.5)])
def test_sdk_timeout(timeout: float, expected: float | None) -> None:
    connection = AnthropicChatModelConnection(api_key="test-key", timeout=timeout)
    with connection.client as client:
        assert client.timeout == expected
        assert client._client.timeout.as_dict() == {
            "connect": expected,
            "read": expected,
            "write": expected,
            "pool": expected,
        }
