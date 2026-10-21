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
"""Chat model setups fail on a blank model instead of sending it.

Each setup is built with only a connection name and the model, so no service
is contacted. vLLM keeps its own message for a blank model and is tested in its
own module.
"""

from typing import Dict, Type

import pytest

from flink_agents.api.chat_models.chat_model import BaseChatModelSetup
from flink_agents.integrations.chat_models import dashscope_chat_model
from flink_agents.integrations.chat_models.anthropic.anthropic_chat_model import (
    DEFAULT_ANTHROPIC_MODEL,
    AnthropicChatModelSetup,
)
from flink_agents.integrations.chat_models.azure.azure_openai_chat_model import (
    AzureOpenAIChatModelSetup,
)
from flink_agents.integrations.chat_models.ollama_chat_model import (
    OllamaChatModelSetup,
)
from flink_agents.integrations.chat_models.openai.openai_chat_model import (
    OpenAIChatModelSetup,
)
from flink_agents.integrations.chat_models.watsonx import watsonx_chat_model

SETUPS: Dict[str, Type[BaseChatModelSetup]] = {
    "OpenAI": OpenAIChatModelSetup,
    "Azure OpenAI": AzureOpenAIChatModelSetup,
    "Anthropic": AnthropicChatModelSetup,
    "Ollama": OllamaChatModelSetup,
    "IBM watsonx.ai": watsonx_chat_model.WatsonxChatModelSetup,
    "DashScope": dashscope_chat_model.DashScopeChatModelSetup,
}

# OpenAI pins its default in its own test module.
DEFAULT_MODELS: Dict[str, str] = {
    "Anthropic": DEFAULT_ANTHROPIC_MODEL,
    "IBM watsonx.ai": watsonx_chat_model.DEFAULT_MODEL,
    "DashScope": dashscope_chat_model.DEFAULT_MODEL,
}


@pytest.mark.parametrize("provider", list(SETUPS))
@pytest.mark.parametrize("model", ["", "   "])
def test_blank_model_rejected(provider: str, model: str) -> None:
    """A blank model raises when the setup is created."""
    with pytest.raises(ValueError, match="Chat model setup model must not be blank."):
        SETUPS[provider](connection="conn", model=model)


@pytest.mark.parametrize("provider", list(DEFAULT_MODELS))
def test_omitted_model_uses_the_default(provider: str) -> None:
    """An omitted model still resolves to the setup's default."""
    setup = SETUPS[provider](connection="conn")

    assert setup.model == DEFAULT_MODELS[provider]
