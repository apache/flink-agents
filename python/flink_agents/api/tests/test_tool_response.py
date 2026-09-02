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
from flink_agents.api.tools import ToolResponse


def test_tool_response_represents_success() -> None:
    response = ToolResponse.success({"answer": 42}, tool_name="calculator")

    assert response.is_success()
    assert not response.is_error()
    assert response.result == {"answer": 42}
    assert str(response) == "{'answer': 42}"


def test_tool_response_represents_failure() -> None:
    response = ToolResponse.error("not found", tool_name="lookup")

    assert response.is_error()
    assert not response.is_success()
    assert response.error_message == "not found"
    assert str(response) == "not found"
