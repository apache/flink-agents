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
from typing import TYPE_CHECKING, List, Type, cast

from flink_agents.api.chat_message import ChatMessage, MessageRole
from flink_agents.api.memory.long_term_memory import MemorySetItem, ReduceSetup
from flink_agents.api.resource import ResourceType
from flink_agents.api.runner_context import RunnerContext

if TYPE_CHECKING:
    from flink_agents.api.chat_models.chat_model import BaseChatModelSetup
    from flink_agents.api.prompts.prompt import Prompt


def summarize(
    memory_set_items: List[MemorySetItem],
    item_type: Type,
    reduce_setup: ReduceSetup,
    ctx: RunnerContext,
) -> ChatMessage:
    """Util functions to summarize the items by llm."""
    # get arguments
    reduce_setup.arguments.get("n")
    model_name = reduce_setup.arguments.get("model")
    prompt = reduce_setup.arguments.get("prompt")

    msgs: List[ChatMessage]
    if item_type == ChatMessage:
        msgs = [item.value for item in memory_set_items]
    else:
        msgs = [
            ChatMessage(role=MessageRole.USER, content=str(item.value))
            for item in memory_set_items
        ]

    # generate summary
    model: BaseChatModelSetup = cast(
        "BaseChatModelSetup",
        ctx.get_resource(name=model_name, type=ResourceType.CHAT_MODEL),
    )
    input_variable = {}
    for msg in msgs:
        input_variable.update(msg.extra_args)

    if prompt is not None:
        if isinstance(prompt, str):
            prompt: Prompt = cast(
                "Prompt",
                ctx.get_resource(prompt, ResourceType.PROMPT),
            )
        prompt_messages = prompt.format_messages(
            role=MessageRole.USER, **input_variable
        )
        msgs.extend(prompt_messages)
    else:
        msgs.append(
            ChatMessage(
                role=MessageRole.USER,
                content="Create a summary of the conversation above",
            )
        )

    response: ChatMessage = model.chat(messages=msgs)

    return response
