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
from pathlib import Path

from pydantic import BaseModel
from pyflink.common import Duration, WatermarkStrategy
from pyflink.datastream import (
    RuntimeExecutionMode,
    StreamExecutionEnvironment,
)
from pyflink.datastream.connectors.file_system import FileSource, StreamFormat

from flink_agents.api.execution_enviroment import AgentsExecutionEnvironment
from flink_agents.examples.stream_agent_declaration import MyWorkflow


class ItemData(BaseModel):
    """Data model for storing item information.

    Attributes:
    ----------
    id : int
        Unique identifier of the item
    review : str
        The user review of the item
    review_score: float
        The review_score of the item
    """

    id: int
    review: str
    review_score: float

    def get_id(self) -> int:
        """Retrieve the unique identifier of the word entry.

        Returns:
        -------
        int
            The ID associated with this word record
        """
        return self.id

    def get_review(self) -> str:
        """Get the word string value being counted.

        Returns:
        -------
        str
            The original word text (case-sensitive)
        """
        return self.review

    def get_review_score(self) -> float:
        """Get the word string value being counted.

        Returns:
        -------
        str
            The original word text (case-sensitive)
        """
        return self.review_score


current_dir = Path(__file__).parent

# if this example raises exception "No module named 'flink_agents'", you could export
#  PYTHONPATH like "export PYTHONPATH=$VENV_HOME/lib/$PYTHON_VERSION/site-packages"
#  before run this example. We will fix this later.
if __name__ == "__main__":
    env = StreamExecutionEnvironment.get_execution_environment()

    # should compile flink-agents jars before run this example.
    env.add_jars(
        f"file:///{current_dir}/../../../runtime/target/flink-agents-runtime-0.1-SNAPSHOT.jar"
    )
    env.add_jars(
        f"file:///{current_dir}/../../../plan/target/flink-agents-plan-0.1-SNAPSHOT.jar"
    )
    env.add_jars(
        f"file:///{current_dir}/../../../api/target/flink-agents-api-0.1-SNAPSHOT.jar"
    )

    env.set_runtime_mode(RuntimeExecutionMode.STREAMING)
    env.set_parallelism(1)

    # currently, bounded source is not supported due to runtime implementation, so
    # we use continuous file source here.
    input_datastream = env.from_source(
        source=FileSource.for_record_stream_format(
            StreamFormat.text_line_format(), f"file:///{current_dir}/resources"
        )
        .monitor_continuously(Duration.of_minutes(1))
        .build(),
        watermark_strategy=WatermarkStrategy.no_watermarks(),
        source_name="streaming_agent_example",
    )

    deserialize_datastream = input_datastream.map(
        lambda x: ItemData.model_validate_json(x)
    )

    agents_env = AgentsExecutionEnvironment.from_env(env).get_execution_environment()
    output_datastream = (
        agents_env.from_datastream(input=deserialize_datastream)
        .apply(MyWorkflow())
        .to_datastream()
    )

    output_datastream.print()

    env.execute()
