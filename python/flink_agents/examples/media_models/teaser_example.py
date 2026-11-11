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
"""Make a product teaser with a key frame, a short clip, and a voiceover.

Set ``DASHSCOPE_API_KEY``, then run:

    python -m flink_agents.examples.media_models.teaser_example

The key frame is 512 by 512. The clip starts from that frame and lasts 3
seconds at 480P. The voiceover reads the product tagline. The job prints the
three media types, not the result URLs.
"""

import sys

from pyflink.common import Configuration
from pyflink.datastream import StreamExecutionEnvironment

from flink_agents.api.execution_environment import AgentsExecutionEnvironment
from flink_agents.api.resource import ResourceDescriptor, ResourceName, ResourceType
from flink_agents.examples.media_models.agents.teaser_agent import (
    TeaserAgent,
    TeaserBrief,
)


def main() -> None:
    """Register the three media models and produce one teaser."""
    config = Configuration()
    config.set_string("python.executable", sys.executable)
    env = StreamExecutionEnvironment.get_execution_environment(config)
    env.set_parallelism(1)
    agents_env = AgentsExecutionEnvironment.get_execution_environment(env)
    _add_media(
        agents_env,
        "still",
        model="qwen-image-3.0",
        task="image_generation",
        poll_interval=2,
        wait_timeout=180,
    )
    _add_media(
        agents_env,
        "clip",
        model="happyhorse-1.1-i2v",
        task="video_generation",
        poll_interval=2,
        wait_timeout=300,
    )
    _add_media(
        agents_env,
        "narration",
        model="qwen-audio-3.0-tts-flash",
        task="speech_synthesis",
        voice="longanhuan_v3.6",
    )

    briefs = env.from_collection(
        [
            TeaserBrief(
                id="notebook-1",
                look="A closed navy notebook on a white desk, soft daylight.",
                motion="Slowly zoom in on the notebook.",
                narration="A notebook for everyday notes.",
            )
        ]
    )
    output = (
        agents_env.from_datastream(input=briefs, key_selector=lambda brief: brief.id)
        .apply(TeaserAgent())
        .to_datastream()
    )
    output.print()
    agents_env.execute("Media Model Teaser Example Job")


def _add_media(
    agents_env: AgentsExecutionEnvironment, name: str, **options: object
) -> None:
    agents_env.add_resource(
        name,
        ResourceType.MEDIA_MODEL,
        ResourceDescriptor(clazz=ResourceName.MediaModel.DASHSCOPE, **options),
    )


if __name__ == "__main__":
    main()
