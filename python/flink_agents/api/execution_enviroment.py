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
import importlib
from abc import ABC, abstractmethod
from typing import Any, Dict, List

from flink_agents.api.agent import Agent


class AgentsExecutionEnvironment(ABC):
    """Base class for agent execution environment."""

    @staticmethod
    def get_execution_environment(**kwargs: Dict[str, Any]) -> 'AgentsExecutionEnvironment':
        """Get agents execution environment.

        Currently, this method only returns LocalExecutionEnvironment. After
        implement other AgentsExecutionEnvironments, this method will return
        appropriate environment according to configuration.

        Returns:
        -------
        AgentsExecutionEnvironment
            Environment for agent execution.
        """
        return importlib.import_module(
            "flink_agents.runtime.local_execution_environment"
        ).get_execution_environment(**kwargs)

    @abstractmethod
    def from_list(self, input: List[Dict[str, Any]]) -> 'AgentsExecutionEnvironment':
        """Set input for agents. Used for local execution.

        Parameters
        ----------
        input : list
            Receive a list as input. The element in the list should be a dict like
            {'key': Any, 'value': Any} or {'value': Any} , extra field will be ignored.
        """

    @abstractmethod
    def apply(self, agent: Agent) -> 'AgentsExecutionEnvironment':
        """Set agent of execution environment.

        Parameters
        ----------
        agent : Agent
            The agent user defined to run in execution environment.
        """

    @abstractmethod
    def to_list(self) -> List[Dict[str, Any]]:
        """Get outputs of agent execution. Used for local execution.

        The element in the list is a dict like {'key': output}.

        Returns:
        -------
        list
            Outputs of agent execution.
        """

    @abstractmethod
    def execute(self) -> None:
        """Execute agents."""
