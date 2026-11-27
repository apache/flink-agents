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
"""Local model setup used by real Java/Python budget integration tests."""

from typing import Any

from flink_agents.api.chat_models.chat_model import BaseChatModelSetup


class BudgetModelSetup(BaseChatModelSetup):
    """Exercise the Python setup with a Java connection and Java tools."""

    @property
    def model_kwargs(self) -> dict[str, Any]:
        return {"tool_choice": "required"}
