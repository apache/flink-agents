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
import json
import logging
from typing import TYPE_CHECKING, Any, Dict, List, Type, cast

from flink_agents.api.chat_message import ChatMessage, MessageRole
from flink_agents.api.memory.long_term_memory import (
    BaseLongTermMemory,
    CompactionConfig,
    MemorySet,
    MemorySetItem,
)
from flink_agents.api.metric_group import MetricGroup
from flink_agents.api.prompts.prompt import Prompt
from flink_agents.api.resource import ResourceType
from flink_agents.api.runner_context import RunnerContext

if TYPE_CHECKING:
    from flink_agents.api.chat_models.chat_model import BaseChatModelSetup


DEFAULT_ANALYSIS_PROMPT = Prompt.from_text("""<role>
Context Summarize Assistant
</role>

<primary_objective>
Your sole objective in this task is to summarize the context above.
</primary_objective>

<objective_information>
You're nearing the total number of input tokens you can accept, so you need compact the context. To achieve this objective, you should extract important topics. Notice,
**The topics must no more than {limit}**. Afterwards, you should generate summarization for each topic, and and record which messages the summary was derived from.
The message index start from 0.
</objective_information>

<output_example>
You must always respond with valid json format in this format:
{"topic1": {"summarization": "User ask what is 1 * 2, and the result is 3.", "messages": [0,1,2]},
 ...
 "topic4": {"summarization": "User ask what's the weather tomorrow, llm use the search_weather, and the answer is snow.", "messages": [9,10,11,12]}
}
</output_example>
""")


def summarize(
    ltm: BaseLongTermMemory,
    memory_set: MemorySet,
    ctx: RunnerContext,
    metric_group: MetricGroup,
    ids: List[str] | None = None,
) -> Dict[str, Any]:
    """Generate summarization of the items in the memory set.

    Will add the summarization to memory set, and delete original items involved
    in summarization.

    Args:
        ltm: The long term memory the memory set belongs to.
        memory_set: The memory set to be summarized.
        ctx: The runner context used to retrieve needed resources.
        metric_group: Metric group used to report metrics.
        ids: The ids of items to be summarized. If not provided, all items will be
        involved in summarization. Optional
    """
    compaction_config: CompactionConfig = memory_set.compaction_config

    # retrieve all items
    items: List[MemorySetItem] = ltm.get(memory_set=memory_set, ids=ids)

    response: ChatMessage = _generate_summarization(
        items, memory_set.item_type, compaction_config, ctx, metric_group
    )

    logging.debug(f"Items to be summarized: {items}\nSummarization: {response.content}")

    for topic in cast("dict", json.loads(response.content)).values():
        summarization = topic["summarization"]
        indices = topic["messages"]

        if compaction_config.limit == 1:
            indices = list(range(len(items)))

        if memory_set.item_type == ChatMessage:
            item = ChatMessage(role=MessageRole.USER, content=summarization)
        else:
            item = summarization

        create_time_list = []
        for index in indices:
            if items[index].compacted:
                create_time_list.append(items[index].created_time.start)
                create_time_list.append(items[index].created_time.end)
            else:
                create_time_list.append(items[index].created_time)

        start = min(create_time_list).isoformat()
        end = max(create_time_list).isoformat()
        last_accessed_time = max(
            [items[index].last_accessed_time for index in indices]
        ).isoformat()

        # delete involved items
        ids = [items[index].id for index in indices]
        ltm.delete(memory_set=memory_set, ids=ids)

        # add summarization to memory set
        ltm.add(
            memory_set=memory_set,
            memory_items=item,
            metadatas={
                "compacted": True,
                "created_time_start": start,
                "created_time_end": end,
                "last_accessed_time": last_accessed_time,
            },
        )

    return response.extra_args


# TODO: Currently, we feed all items to the LLM at once, which may exceed the LLM's
# context window. We need to support batched summary generation.
def _generate_summarization(
    memory_set_items: List[MemorySetItem],
    item_type: Type,
    compaction_config: CompactionConfig,
    ctx: RunnerContext,
    metric_group: MetricGroup
) -> ChatMessage:
    """Generate summarization of the items by llm."""
    # get arguments
    model_name = compaction_config.model
    prompt = compaction_config.prompt

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
        ctx.get_resource(name=model_name, type=ResourceType.CHAT_MODEL, metric_group=metric_group),
    )
    input_variable = {}
    for msg in msgs:
        input_variable.update(msg.extra_args)

    if prompt is not None:
        if isinstance(prompt, str):
            prompt: Prompt = cast(
                "Prompt",
                ctx.get_resource(prompt, ResourceType.PROMPT, metric_group=metric_group),
            )
        prompt_messages = prompt.format_messages(
            role=MessageRole.USER, **input_variable
        )
        msgs.extend(prompt_messages)
    else:
        msgs.extend(DEFAULT_ANALYSIS_PROMPT.format_messages(limit=str(compaction_config.limit)))

    response: ChatMessage = model.chat(messages=msgs)

    return response
