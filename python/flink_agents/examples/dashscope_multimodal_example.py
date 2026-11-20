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
#  limitations under the License.
################################################################################
"""Describe an image in a Flink job. Set DASHSCOPE_API_KEY before running."""

from pyflink.common import Types
from pyflink.datastream import StreamExecutionEnvironment

from flink_agents.api.execution_environment import AgentsExecutionEnvironment
from flink_agents.api.resource import ResourceDescriptor, ResourceName, ResourceType
from flink_agents.examples.dashscope_multimodal_agent import DashScopeMultimodalAgent

_IMAGE_URL = (
    "https://help-static-aliyun-doc.aliyuncs.com/file-manage-files/zh-CN/"
    "20241022/emyrja/dog_and_girl.jpeg"
)


def main() -> None:
    """Register the vision model and run one image request."""
    env = StreamExecutionEnvironment.get_execution_environment()
    env.set_parallelism(1)
    agents_env = AgentsExecutionEnvironment.get_execution_environment(env)
    agents_env.add_resource(
        "dashscope_connection",
        ResourceType.CHAT_MODEL_CONNECTION,
        ResourceDescriptor(clazz=ResourceName.ChatModel.DASHSCOPE_CONNECTION),
    )
    agents_env.add_resource(
        "dashscope_vision",
        ResourceType.CHAT_MODEL,
        ResourceDescriptor(
            clazz=ResourceName.ChatModel.DASHSCOPE_SETUP,
            connection="dashscope_connection",
            model="qwen3-vl-plus",
            api_type="multimodal",
        ),
    )

    images = env.from_collection([_IMAGE_URL])
    descriptions = (
        agents_env.from_datastream(images, key_selector=lambda url: url)
        .apply(DashScopeMultimodalAgent())
        .to_datastream(output_type=Types.STRING())
    )
    descriptions.print()
    agents_env.execute("DashScope Image Understanding")


if __name__ == "__main__":
    main()
