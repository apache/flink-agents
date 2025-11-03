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
from typing import TYPE_CHECKING

from flink_agents.api.agent import Agent
from flink_agents.api.chat_message import ChatMessage, MessageRole
from flink_agents.api.decorators import (
    action,
    chat_model_setup,
    prompt,
    tool,
)
from flink_agents.api.events.chat_event import ChatRequestEvent, ChatResponseEvent
from flink_agents.api.events.event import InputEvent, OutputEvent
from flink_agents.api.prompts.prompt import Prompt
from flink_agents.api.resource import ResourceDescriptor
from flink_agents.api.runner_context import RunnerContext
from flink_agents.examples.quickstart.agents.alert_types_and_resources import (
    AlertInfoAnalysisRes,
    notify_shipping_manager,
    info_analysis_prompt,
    AlertInfo,
)
from flink_agents.integrations.chat_models.ollama_chat_model import (
    OllamaChatModelSetup,
)

if TYPE_CHECKING:
    from flink_agents.examples.quickstart.agents.alert_types_and_resources import (
        AlertInfo,
    )


class InfoAnalysisAgent(Agent):
    """An agent that uses a large language model (LLM) to analyze Alert infos
    and generate a satisfaction score and potential reasons for dissatisfaction.

    This agent receives a Alert info and produces a satisfaction score and a list
    of reasons for dissatisfaction. It handles prompt construction, LLM interaction,
    and output parsing.
    """

    @prompt
    @staticmethod
    def info_analysis_prompt() -> Prompt:
        """Prompt for info analysis."""
        return info_analysis_prompt

    @tool
    @staticmethod
    def notify_shipping_manager(id: str, info: str) -> None:
        """Notify the shipping manager when Alert received a negative info due to
        shipping damage.

        Parameters
        ----------
        id : str
            The id of the Alert that received a negative info due to shipping damage
        info: str
            The negative info content
        """
        # reuse the declared function, but for parsing the tool metadata, we write doc
        # string here again.
        notify_shipping_manager(id=id, info=info)

    @chat_model_setup
    @staticmethod
    def info_analysis_model() -> ResourceDescriptor:
        """ChatModel which focus on info analysis."""
        return ResourceDescriptor(
            clazz=OllamaChatModelSetup,
            connection="ollama_server",
            model="qwen3:14b",
            prompt="info_analysis_prompt",
            tools=["notify_shipping_manager"],
            extract_reasoning=True,
        )

    @action(InputEvent)
    @staticmethod
    def process_input(event: InputEvent, ctx: RunnerContext) -> None:
        """Process input event and send chat request for info analysis."""
        input: AlertInfo = event.input
        ctx.short_term_memory.set("id", input.id)
        ctx.short_term_memory.set("input_alert_info_json", input.model_dump_json())

        content = f"""
            "id": {input.id},
            "info": {input.info}
        """
        msg = ChatMessage(role=MessageRole.USER, extra_args={"input": content})
        ctx.send_event(ChatRequestEvent(model="info_analysis_model", messages=[msg]))

    @action(ChatResponseEvent)
    @staticmethod
    def process_chat_response(event: ChatResponseEvent, ctx: RunnerContext) -> None:
        """Process chat response event and send output event."""
        try:
            json_content = json.loads(event.response.content)
            ctx.send_event(
                OutputEvent(
                    output=AlertInfoAnalysisRes(
                        id=ctx.short_term_memory.get("id"),
                        location=json_content["location"],
                        severity_level=json_content["severity_level"],
                        reasons=json_content["reasons"],
                        suggestions=json_content["suggestions"],
                    )
                )
            )
        except Exception:
            logging.exception(
                f"Error processing chat response {event.response.content}"
            )
            original_json = ctx.short_term_memory.get("input_alert_info_json")
            if original_json is not None:
                # 记录失败的原始响应并将其附加到用于重试的输入中
                failed_raw = str(event.response.content)
                ctx.short_term_memory.set("last_failed_response_raw", failed_raw)
                original_input = AlertInfo.model_validate_json(original_json)
                appended_info = f"{original_input.info}\n[previous_llm_response]: {failed_raw}\nLLM 的输出必须严格遵循标准的 JSON 格式"
                retry_input = AlertInfo(id=original_input.id, info=appended_info)
                ctx.short_term_memory.set("retry_input_json", retry_input.model_dump_json())

                # 直接复用被 @action 装饰的静态方法，保持事件驱动规范
                InfoAnalysisAgent.process_input(InputEvent(input=retry_input), ctx)
            else:
                logging.warning("Missing original input in short-term memory, skip retry.")
