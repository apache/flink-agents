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
################################################################################
"""Real Flink Python agents load Skills from job JARs, via API and YAML."""

import re
import sys
import sysconfig
import zipfile
from pathlib import Path

import pytest
from pyflink.common import Configuration, Encoder
from pyflink.common.typeinfo import Types
from pyflink.datastream import StreamExecutionEnvironment
from pyflink.datastream.connectors.file_system import StreamingFileSink

from flink_agents.api.agents.agent import Agent
from flink_agents.api.decorators import action, skills
from flink_agents.api.events.event import Event, OutputEvent
from flink_agents.api.events.event_type import EventType
from flink_agents.api.execution_environment import AgentsExecutionEnvironment
from flink_agents.api.resource import ResourceType
from flink_agents.api.runner_context import RunnerContext
from flink_agents.api.skills import Skills


def read_skill(event: Event, ctx: RunnerContext) -> None:
    """Load through the runtime cache and the standard built-in tool."""
    tool = ctx.get_resource("load_skill", ResourceType.TOOL)
    body = tool.call(name="demo")
    attachment = tool.call(name="demo", path="references/example.txt")
    ctx.send_event(OutputEvent(output=f"{body}\nAttachment: {attachment}"))


class ClasspathAgent(Agent):
    @skills
    @staticmethod
    def packaged_skills() -> Skills:
        return Skills.from_classpath("issue1194-skills")

    @action(EventType.InputEvent)
    @staticmethod
    def process_input(event: Event, ctx: RunnerContext) -> None:
        read_skill(event, ctx)


@pytest.mark.parametrize("yaml", [False, True])
def test_python_agent_reads_job_jar(tmp_path: Path, yaml: bool) -> None:
    jar = tmp_path / "skills.jar"
    with zipfile.ZipFile(jar, "w") as archive:
        # No directory entries: classloader fallback must discover the prefix.
        archive.writestr(
            "issue1194-skills/demo/SKILL.md",
            "---\nname: demo\ndescription: JAR skill\n---\nInstructions from JAR",
        )
        archive.writestr(
            "issue1194-skills/demo/references/example.txt", "jar-attachment"
        )
    config = Configuration()
    config.set_string("python.executable", sys.executable)
    config.set_string("python.pythonpath", sysconfig.get_paths()["purelib"])
    env = StreamExecutionEnvironment.get_execution_environment(config)
    env.set_parallelism(1)
    env.add_jars(jar.as_uri())
    agents = AgentsExecutionEnvironment.get_execution_environment(env=env)
    agent = ClasspathAgent()
    if yaml:
        declaration = tmp_path / "agent.yaml"
        declaration.write_text("""agents:
  - name: classpath_agent
    skills:
      - name: packaged
        classpath: [issue1194-skills]
    actions:
      - name: read_skill
        type: python
        function: flink_agents.e2e_tests.e2e_tests_resource_cross_language.skills_cross_language_test:read_skill
        trigger_conditions: [input]
""")
        agents.load_yaml(declaration)
        agent = "classpath_agent"
    output = (
        agents.from_datastream(
            input=env.from_collection(["read"]), key_selector=lambda _: "key"
        )
        .apply(agent)
        .to_datastream(Types.STRING())
    )
    result_dir = tmp_path / "results"
    output.add_sink(
        StreamingFileSink.for_row_format(
            str(result_dir), Encoder.simple_string_encoder()
        ).build()
    )
    agents.execute()
    result = "".join(
        path.read_text()
        for path in result_dir.rglob("*")
        if path.is_file() and not path.name.endswith(".crc")
    )
    assert "Instructions from JAR" in result
    assert "Attachment: jar-attachment" in result
    directory = re.search(r"Base directory for this skill: ([^\n]+)", result)
    assert directory is not None
    assert not Path(directory.group(1)).exists()
    assert jar.exists()
