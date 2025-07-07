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

from flink_agents.api.execution_enviroment import AgentsExecutionEnvironment
from flink_agents.api.agent import Agent
from flink_agents.runtime.local_runner import LocalRunner


class LocalExecutionEnvironment(AgentsExecutionEnvironment):
    """Implementation of AgentsExecutionEnvironment for local execution environment."""

    __input: List[Dict[str, Any]]
    __output: List[Any]
    __runner: LocalRunner = None
    __executed: bool = False

    def __init__(self) -> None:
        """Init empty output list."""
        self.__output = []

    def from_list(self, input: list) -> 'AgentsExecutionEnvironment':
        """Set input list of execution environment."""
        self.__input = input
        return self

    def apply(self, agent: Agent) -> 'AgentsExecutionEnvironment':
        """Create local runner to execute given agent.

        Doesn't support apply multiple agents.
        """
        if self.__runner is not None:
            err_msg = "LocalExecutionEnvironment doesn't support apply multiple agents."
            raise RuntimeError(err_msg)
        self.__runner = LocalRunner(agent)
        return self

    def to_list(self) -> list:
        """Get output list of execution environment."""
        return self.__output

    def execute(self) -> None:
        """Execute agents.

        Doesn't support execute multiple times.
        """
        if self.__executed:
            err_msg = "LocalExecutionEnvironment doesn't support execute multiple times."
            raise RuntimeError(err_msg)
        self.__executed = True
        for input in self.__input:
            self.__runner.run(**input)
        outputs = self.__runner.get_outputs()
        for output in outputs:
            self.__output.append(output)


def get_execution_environment(**kwargs: Dict[str, Any]) -> AgentsExecutionEnvironment:
    """Factory function to create a local agents execution environment.

    Parameters
    ----------
    **kwargs : Dict[str, Any]
        The dict of parameters to configure the execution environment.

    Returns:
    -------
    AgentsExecutionEnvironment
        A configured agents execution environment instance.
    """
    return LocalExecutionEnvironment()
