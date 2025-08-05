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
from typing import Any, Dict, List

from pyflink.common import TypeInformation
from pyflink.datastream import DataStream, KeySelector, StreamExecutionEnvironment
from pyflink.table import Schema, StreamTableEnvironment, Table

from flink_agents.api.agent import Agent
from flink_agents.api.execution_environment import (
    AgentBuilder,
    AgentsExecutionEnvironment,
)
from flink_agents.runtime.local_runner import LocalRunner


class LocalAgentBuilder(AgentBuilder):
    """LocalAgentBuilder for building agent instance."""

    __env: "LocalExecutionEnvironment"
    __input: List[Dict[str, Any]]
    __output: List[Any]
    __runner: LocalRunner = None
    __executed: bool = False

    def __init__(
        self, env: "LocalExecutionEnvironment", input: List[Dict[str, Any]]
    ) -> None:
        """Init empty output list."""
        self.__env = env
        self.__input = input
        self.__output = []

    def apply(self, agent: Agent) -> AgentBuilder:
        """Create local runner to execute given agent.

        Doesn't support apply multiple Agents.
        """
        if self.__runner is not None:
            err_msg = "LocalAgentBuilder doesn't support apply multiple agents."
            raise RuntimeError(err_msg)
        self.__runner = LocalRunner(agent)
        self.__env.set_agent(self.__input, self.__output, self.__runner)
        return self

    def to_list(self) -> List[Dict[str, Any]]:
        """Get output list of execution environment."""
        return self.__output

    def to_datastream(self) -> DataStream:
        """Get output DataStream of agent execution.

        This method is not supported for LocalAgentBuilder.
        """
        msg = "LocalAgentBuilder does not support to_datastream."
        raise NotImplementedError(msg)

    def to_table(self, schema: Schema, output_type: TypeInformation) -> Table:
        """Get output Table of agent execution.

        This method is not supported for LocalAgentBuilder.
        """
        msg = "LocalAgentBuilder does not support to_table."
        raise NotImplementedError(msg)


class LocalExecutionEnvironment(AgentsExecutionEnvironment):
    """Implementation of AgentsExecutionEnvironment for local execution environment."""

    __input: List[Dict[str, Any]] = None
    __output: List[Any] = None
    __runner: LocalRunner = None
    __executed: bool = False

    def from_list(self, input: list) -> LocalAgentBuilder:
        """Set input list of execution environment."""
        if self.__input is not None:
            err_msg = "LocalExecutionEnvironment doesn't support call from_list multiple times."
            raise RuntimeError(err_msg)

        self.__input = input
        return LocalAgentBuilder(env=self, input=input)

    def set_agent(self, input: list, output: list, runner: LocalRunner) -> None:
        """Set agent input, output and runner."""
        self.__input = input
        self.__runner = runner
        self.__output = output

    def execute(self) -> None:
        """Execute agent individually."""
        if self.__executed:
            err_msg = (
                "LocalExecutionEnvironment doesn't support execute multiple times."
            )
            raise RuntimeError(err_msg)
        self.__executed = True
        for input in self.__input:
            self.__runner.run(**input)
        outputs = self.__runner.get_outputs()
        for output in outputs:
            self.__output.append(output)

    def from_datastream(
        self, input: DataStream, key_selector: KeySelector = None
    ) -> AgentBuilder:
        """Set input DataStream of agent execution.

        This method is not supported for local execution environments.
        """
        msg = "LocalExecutionEnvironment does not support from_datastream."
        raise NotImplementedError(msg)

    def from_table(
        self,
        input: Table,
        t_env: StreamTableEnvironment,
        key_selector: KeySelector = None,
    ) -> AgentBuilder:
        """Set input Table of agent execution.

        This method is not supported for local execution environments.
        """
        msg = "LocalExecutionEnvironment does not support from_table."
        raise NotImplementedError(msg)


def create_instance(
    env: StreamExecutionEnvironment, **kwargs: Dict[str, Any]
) -> AgentsExecutionEnvironment:
    """Factory function to create a remote agents execution environment.

    Parameters
    ----------
    env : StreamExecutionEnvironment
        Flink job execution environment.
    **kwargs : Dict[str, Any]
        The dict of parameters to configure the execution environment.

    Returns:
    -------
    AgentsExecutionEnvironment
        A configured agents execution environment instance.
    """
    return LocalExecutionEnvironment()
