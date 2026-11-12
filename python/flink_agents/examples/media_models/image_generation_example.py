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
"""Generate an image in a Flink Agents job.

Set ``DASHSCOPE_API_KEY``, then run:

    python -m flink_agents.examples.media_models.image_generation_example

``generate()`` submits the image task and waits for its result. The job prints
that result, including the image URL.
"""

import sys

from pyflink.common import Configuration
from pyflink.datastream import StreamExecutionEnvironment

from flink_agents.api.execution_environment import AgentsExecutionEnvironment
from flink_agents.api.resource import ResourceDescriptor, ResourceName, ResourceType
from flink_agents.examples.media_models.agents.image_generation_agent import (
    ImageGenerationAgent,
    ImagePrompt,
)


def main() -> None:
    """Register one image model and generate an image from a prompt."""
    config = Configuration()
    config.set_string("python.executable", sys.executable)
    env = StreamExecutionEnvironment.get_execution_environment(config)
    env.set_parallelism(1)
    agents_env = AgentsExecutionEnvironment.get_execution_environment(env)
    agents_env.add_resource(
        "image",
        ResourceType.MEDIA_MODEL,
        ResourceDescriptor(
            clazz=ResourceName.MediaModel.DASHSCOPE,
            model="qwen-image-3.0",
            task="image_generation",
            poll_interval=2,
            wait_timeout=180,
        ),
    )
    prompts = env.from_collection(
        [ImagePrompt(id="notebook-1", prompt="A navy notebook on a white desk.")]
    )
    output = (
        agents_env.from_datastream(input=prompts, key_selector=lambda prompt: prompt.id)
        .apply(ImageGenerationAgent())
        .to_datastream()
    )
    output.print()
    agents_env.execute("Media Model Image Generation Example Job")


if __name__ == "__main__":
    main()
