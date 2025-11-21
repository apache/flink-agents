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
import os
from pathlib import Path

import pytest

from flink_agents.api.execution_environment import AgentsExecutionEnvironment
from flink_agents.e2e_tests.chat_model_integration_agent import ChatModelTestAgent
from flink_agents.e2e_tests.ollama_prepare_utils import pull_model

current_dir = Path(__file__).parent

TONGYI_MODEL = os.environ.get("TONGYI_CHAT_MODEL", "qwen-plus")
os.environ["TONGYI_CHAT_MODEL"] = TONGYI_MODEL
OLLAMA_MODEL = os.environ.get("OLLAMA_CHAT_MODEL", "qwen3:1.7b")
os.environ["OLLAMA_CHAT_MODEL"] = OLLAMA_MODEL
DASHSCOPE_API_KEY = os.environ.get("DASHSCOPE_API_KEY")

client = pull_model(OLLAMA_MODEL)


@pytest.mark.parametrize(
    "model_provider",
    [
        pytest.param(
            "Ollama",
            marks=pytest.mark.skipif(
                client is None,
                reason="Ollama client is not available or test model is missing.",
            ),
        ),
        pytest.param(
            "Tongyi",
            marks=pytest.mark.skipif(
                DASHSCOPE_API_KEY is None, reason="Tongyi api key is not set."
            ),
        ),
    ],
)
def test_chat_model_integration(model_provider: str) -> None:  # noqa: D103
    os.environ["MODEL_PROVIDER"] = model_provider
    env = AgentsExecutionEnvironment.get_execution_environment()
    input_list = []
    agent = ChatModelTestAgent()

    output_list = env.from_list(input_list).apply(agent).to_list()

    input_list.append({"key": "0001", "value": "calculate the sum of 1 and 2."})
    input_list.append({"key": "0002", "value": "Tell me a joke about cats."})

    env.execute()

    for output in output_list:
        for key, value in output.items():
            print(f"{key}: {value}")
