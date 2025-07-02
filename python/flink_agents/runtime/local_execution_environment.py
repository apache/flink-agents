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

from pyflink.datastream import DataStream, KeySelector

from flink_agents.api.execution_enviroment import AgentsExecutionEnvironment
from flink_agents.api.workflow import Workflow
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

    def from_list(self, input: list) -> "AgentsExecutionEnvironment":
        """Set input list of execution environment."""
        self.__input = input
        return self

    def apply(self, workflow: Workflow) -> "AgentsExecutionEnvironment":
        """Create local runner to execute given workflow.

        Doesn't support apply multiple workflows.
        """
        if self.__runner is not None:
            err_msg = (
                "LocalExecutionEnvironment doesn't support apply multiple workflows."
            )
            raise RuntimeError(err_msg)
        self.__runner = LocalRunner(workflow)
        return self

    def to_list(self) -> list:
        """Get output list of execution environment."""
        return self.__output

    def execute(self) -> None:
        """Execute agents.

        Doesn't support execute multiple times.
        """
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
    ) -> "AgentsExecutionEnvironment":
        """Set input DataStream of workflow execution.

        This method is not supported for local execution environments.
        """
        msg = "LocalExecutionEnvironment does not support from_datastream."
        raise NotImplementedError(msg)

    def to_datastream(self) -> DataStream:
        """Get output DataStream of workflow execution.

        This method is not supported for local execution environments.
        """
        msg = "LocalExecutionEnvironment does not support to_datastream."
        raise NotImplementedError(msg)


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
