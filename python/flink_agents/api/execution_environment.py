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
from typing import Any, Dict, List, Optional

from pyflink.common import TypeInformation
from pyflink.datastream import DataStream, KeySelector, StreamExecutionEnvironment
from pyflink.table import Schema, StreamTableEnvironment, Table

from flink_agents.api.agent import Agent


class AgentBuilder(ABC):
    """Builder for integrating agent with input and output."""

    @abstractmethod
    def apply(self, agent: Agent) -> "AgentBuilder":
        """Set agent of AgentBuilder.

        Parameters
        ----------
        agent : Agent
            The agent user defined to run in execution environment.
        """

    @abstractmethod
    def to_list(self) -> List[Dict[str, Any]]:
        """Get output list of agent execution.

        The element in the list is a dict like {'key': output}.

        Returns:
        -------
        list
            Outputs of agent execution.
        """

    @abstractmethod
    def to_datastream(self) -> DataStream:
        """Get output datastream of agent execution.

        Returns:
        -------
        DataStream
            Output datastream of agent execution.
        """

    # TODO: auto generate output_type.
    @abstractmethod
    def to_table(self, schema: Schema, output_type: TypeInformation) -> Table:
        """Get output table of agent execution.

        Parameters
        ----------
        schema : Schema
            Indicate schema of the output table.
        output_type : TypeInformation
            Indicate schema corresponding type information.

        Returns:
        -------
        Table
            Output table of agent execution.
        """


class AgentsExecutionEnvironment(ABC):
    """Base class for agent execution environment."""

    @staticmethod
    def get_execution_environment(
        env: Optional[StreamExecutionEnvironment] = None, **kwargs: Dict[str, Any]
    ) -> "AgentsExecutionEnvironment":
        """Get agents execution environment.

        Currently, user can run flink agents in ide using LocalExecutionEnvironment or
        RemoteExecutionEnvironment. To distinguish which environment to use, when run
        flink agents with pyflink datastream/table, user should pass flink
        StreamExecutionEnvironment when get AgentsExecutionEnvironment.

        Returns:
        -------
        AgentsExecutionEnvironment
            Environment for agent execution.
        """
        if env is None:
            return importlib.import_module(
                "flink_agents.runtime.local_execution_environment"
            ).create_instance(env=env, **kwargs)
        else:
            return importlib.import_module(
                "flink_agents.runtime.remote_execution_environment"
            ).create_instance(env=env, **kwargs)

    @abstractmethod
    def from_list(self, input: List[Dict[str, Any]]) -> AgentBuilder:
        """Set input for agents. Used for local execution.

        Parameters
        ----------
        input : list
            Receive a list as input. The element in the list should be a dict like
            {'key': Any, 'value': Any} or {'value': Any} , extra field will be ignored.

        Returns:
        -------
        AgentBuilder
            A new builder to build an agent for specific input.
        """

    @abstractmethod
    def from_datastream(
        self, input: DataStream, key_selector: Optional[KeySelector] = None
    ) -> AgentBuilder:
        """Set input for agents. Used for remote execution.

        Parameters
        ----------
        input : DataStream
            Receive a DataStream as input.
        key_selector : KeySelector
            Extract key from each input record.

        Returns:
        -------
        AgentBuilder
            A new builder to build an agent for specific input.
        """

    @abstractmethod
    def from_table(
        self,
        input: Table,
        t_env: StreamTableEnvironment,
        key_selector: Optional[KeySelector] = None,
    ) -> AgentBuilder:
        """Set input for agents. Used for remote execution.

        Parameters
        ----------
        input : Table
            Receive a Table as input.
        t_env: StreamTableEnvironment
            table environment supports convert Table to/from DataStream.
        key_selector : KeySelector
            Extract key from each input record.

        Returns:
        -------
        AgentBuilder
            A new builder to build an agent for specific input.
        """

    @abstractmethod
    def execute(self) -> None:
        """Execute agent individually."""
