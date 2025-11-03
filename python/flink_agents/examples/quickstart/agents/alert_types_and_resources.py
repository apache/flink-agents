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
from this import d
from typing import List

from pydantic import BaseModel

from flink_agents.api.chat_message import ChatMessage, MessageRole
from flink_agents.api.prompts.prompt import Prompt
from flink_agents.api.resource import ResourceDescriptor
from flink_agents.integrations.chat_models.ollama_chat_model import (
    OllamaChatModelConnection,
)

# Prompt for info analysis agent.
info_analysis_system_prompt_str = """
    分析告警信息（结合异常详情和异常消息）并评估告警内容的严重程度，分析告警原因并给出优化建议。
    输入格式示例：
    {{
        "id":"2024-08-14T14:06", 
        "review": "告警来源:zone-platform:python prov-host-stats-detect.py,异常ID:安徽-20240814140509-538,异常位置:安徽,异常消息:主机异常|主机数量:1/57,异常级别:中,异常详情:{cluster_name: Cluster-AnHui, bad_hosts_list: [ah-hnidc-cdn-crs4-27:<Node Lost:无监控数据>], bad_hosts_percent: 1/57},异常负责人:[YangMingshan, TianGuangYa]"}
    }}
    
    输出格式示例：
    {{
        "id": "2024-08-14T14:06",
        "location": "安徽",
        "severity_level": "中",
        "reasons": "主机异常，异常数量为1/57，原因是没有监控数据，无影响"，
        "suggestions": "1. 增加监控数据，避免无影响的异常\n2. 检查主机状态，及时处理异常"
    }}
    要求：
​    1. 严重程度需根据异常消息、异常级别、异常详情综合分析，分为高，中，低三个级别
​    2. 告警原因需综合异常消息和异常详情中的关键信息进行描述
​    3. 必须输出标准的JSON格式，包含"id"、"location"、"severity_level"、"reasons"、"suggestions"
    4. 所有字段值必须为中文字符串
​    """
    

info_analysis_prompt = Prompt.from_messages(
    messages=[
        ChatMessage(
            role=MessageRole.SYSTEM,
            content=info_analysis_system_prompt_str,
        ),
        # Here we just fill the prompt with input, user should deserialize
        # input element to input text self in action.
        ChatMessage(
            role=MessageRole.USER,
            content="""
            "input":
            {input}
            """,
        ),
    ],
)

# Prompt for info analysis react agent.
info_analysis_react_prompt = Prompt.from_messages(
    messages=[
        ChatMessage(
            role=MessageRole.SYSTEM,
            content=info_analysis_system_prompt_str,
        ),
        # For react agent, if the input element is not primitive types,
        # framework will deserialize input element to dict and fill the prompt.
        # Note, the input element should be primitive types, BaseModel or Row.
        ChatMessage(
            role=MessageRole.USER,
            content="""
            "id": {id},
            "info": {info}
            """,
        ),
    ],
)

# Prompt for Alert suggestion agent.
Alert_suggestion_prompt_str = """
        You are a computer operations and maintenance expert. Based on the following alert causes, generate executable optimization suggestions. 

        Input format:
        {{
            "id": "1",
            "id_list": ["1", "2", "3"],
            "alert_reasons": ["reason1", "reason2", "reason3"]
        }}

        Ensure that your response can be parsed by Python json,use the following format as an example:
        {{
            "suggestion_list": [
                "suggestion1",
                "suggestion2",
                "suggestion3"
            ]
        }}

        input:
        {input}
        """
Alert_suggestion_prompt = Prompt.from_text(Alert_suggestion_prompt_str)

Final_alert_suggestion_prompt_str = """
        You are a computer operations and maintenance expert. Based on the following alert causes, please provide a concise summary of the alert causes and recommendations.

        Input format:
        {{
            "timestamp": '25-11-02-21:12',
            "id_list": ["1", "2", "3"],
            "alert_reasons": ["reason1", "reason2", "reason3"],
            "suggestion_list": ["suggestion1", "suggestion2", "suggestion3"]
        }}

        Ensure that your response can be parsed by Python json,use the following format as an example:
        {{
            "alert_reasons_suggestions": {{"reason_a":"suggestion_a", "reason_b":"suggestion_b"}}
        }}

        input:
        {input}
        """
Final_alert_suggestion_prompt = Prompt.from_text(Final_alert_suggestion_prompt_str)


# Tool for notifying the shipping manager. For simplicity, just print the message.
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
    content = (
        f"Transportation issue for Alert [{id}], the customer feedback: {info}"
    )
    print(content)


# Custom types used for Alert suggestion agent
class AlertInfoSummary(BaseModel):
    """Aggregates multiple infos and insights using LLM for a product.

    Attributes:
        id (str): The unique identifier of the product.
        score_hist (List[str]): A collection of rating scores from various infos.
        unsatisfied_reasons (List[str]): A list of reasons or insights generated by LLM
            to explain the rating.
    """

    timestamp: str
    id_list: List[str]
    alert_reasons: List[str]


class AlertSuggestion(BaseModel):
    """Provides a summary of info data including suggestions for improvement.

    Attributes:
        id (str): The unique identifier of the product.
        score_histogram (List[int]): A collection of rating scores from various infos.
        suggestions (List[str]): Suggestions or recommendations generated as a result of
            info analysis.
    """

    timestamp: str
    id_list: List[str]
    alert_reasons: List[str]
    suggestion_list: List[str]

class FinalAlertSuggestion(BaseModel):

    timestamp: str
    alert_reasons_suggestions: dict



# custom types for info analysis agent.
class AlertInfo(BaseModel):
    """Data model representing a Alert info.

    Attributes:
    ----------
    id : str
        The unique identifier for the Alert being infoed.
    info : str
        The info of the product.
    """

    id: str
    info: str


class AlertInfoAnalysisRes(BaseModel):
    """Data model representing analysis result of a Alert info.

    Attributes:
    ----------
    "id": "2024-08-14T14:06",
    "location": "安徽",
    "severity_level": "中",
    "reasons": "主机异常，异常数量为1/57，原因是没有监控数据，无影响"，
    "suggestions": "1. 增加监控数据，避免无影响的异常\n2. 检查主机状态，及时处理异常"
    """

    id: str
    location: str
    severity_level: str
    reasons: str
    suggestions: str



# ollama chat model connection descriptor
ollama_server_descriptor = ResourceDescriptor(
    clazz=OllamaChatModelConnection, request_timeout=120
)
