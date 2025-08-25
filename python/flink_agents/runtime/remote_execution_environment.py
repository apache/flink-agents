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
from typing import Any, Callable, Dict, List, Optional, Tuple, Type

import cloudpickle
from pyflink.common import TypeInformation
from pyflink.common.typeinfo import (
    PickledBytesTypeInfo,
)
from pyflink.datastream import (
    DataStream,
    KeyedStream,
    KeySelector,
    StreamExecutionEnvironment,
)
from pyflink.table import Schema, StreamTableEnvironment, Table
from pyflink.util.java_utils import invoke_method

from flink_agents.api.agent import Agent
from flink_agents.api.events.event import Event
from flink_agents.api.execution_environment import (
    AgentBuilder,
    AgentsExecutionEnvironment,
)
from flink_agents.api.resource import ResourceType
from flink_agents.plan.agent_plan import AgentPlan


class RemoteAgentBuilder(AgentBuilder):
    """RemoteAgentBuilder for integrating datastream/table and agent."""

    __input: DataStream
    __agent_plan: AgentPlan = None
    __output: DataStream = None
    __t_env: StreamTableEnvironment
    __actions: Dict[str, Tuple[List[Type[Event]], Callable]] = None
    __resources: Dict[ResourceType, Dict[str, Any]] = None

    def __init__(
        self,
        input: DataStream,
        t_env: Optional[StreamTableEnvironment] = None,
        actions: Optional[Dict[str, Tuple[List[Type[Event]], Callable]]] = None,
        resources: Optional[Dict[ResourceType, Dict[str, Any]]] = None,
    ) -> None:
        """Init method of RemoteAgentBuilder."""
        self.__input = input
        self.__t_env = t_env
        self.__actions = actions
        self.__resources = resources

    def apply(self, agent: Agent) -> "AgentBuilder":
        """Set agent of execution environment.

        Parameters
        ----------
        agent : Agent
            The agent user defined to run in execution environment.
        """
        if self.__agent_plan is not None:
            err_msg = "RemoteAgentBuilder doesn't support apply multiple agents yet."
            raise RuntimeError(err_msg)

        # inspect refer actions and resources from env to agent.
        for action_name in agent._action_names:
            agent.actions[action_name] = self.__actions[action_name]
        for type, names in agent._resource_names.items():
            if type not in agent.resources:
                agent.resources[type] = {}
            for name in names:
                agent.resources[type][name] = self.__resources[type][name]

        self.__agent_plan = AgentPlan.from_agent(agent)
        return self

    def to_datastream(
        self, output_type: Optional[TypeInformation] = None
    ) -> DataStream:
        """Get output datastream of agent execution.

        Returns:
        -------
        DataStream
            Output datastream of agent execution.
        """
        if self.__agent_plan is None:
            err_msg = "Must apply agent before call to_datastream/to_table."
            raise RuntimeError(err_msg)

        # return the same output datastream when call to_datastream multiple.
        if self.__output is None:
            j_data_stream_output = invoke_method(
                None,
                "org.apache.flink.agents.runtime.CompileUtils",
                "connectToAgent",
                [
                    self.__input._j_data_stream,
                    self.__agent_plan.model_dump_json(serialize_as_any=True),
                ],
                [
                    "org.apache.flink.streaming.api.datastream.KeyedStream",
                    "java.lang.String",
                ],
            )
            output_stream = DataStream(j_data_stream_output)
            self.__output = output_stream.map(
                lambda x: cloudpickle.loads(x), output_type=output_type
            )
        return self.__output

    def to_table(self, schema: Schema, output_type: TypeInformation) -> Table:
        """Get output Table of agent execution.

        Parameters
        ----------
        schema : Schema
            Indicate schema of the output table.
        output_type : TypeInformation
            Indicate schema corresponding type information.

        Returns:
        -------
        Table
            Output Table of agent execution.
        """
        return self.__t_env.from_data_stream(self.to_datastream(output_type), schema)

    def to_list(self) -> List[Dict[str, Any]]:
        """Get output list of agent execution.

        This method is not supported for remote execution environments.
        """
        msg = "RemoteAgentBuilder does not support to_list."
        raise NotImplementedError(msg)


class RemoteExecutionEnvironment(AgentsExecutionEnvironment):
    """Implementation of AgentsExecutionEnvironment for execution with DataStream."""

    __env: StreamExecutionEnvironment

    def __init__(self, env: StreamExecutionEnvironment) -> None:
        """Init method of RemoteExecutionEnvironment."""
        super().__init__()
        self.__env = env

    @staticmethod
    def __process_input_datastream(
        input: DataStream, key_selector: Optional[KeySelector] = None
    ) -> KeyedStream:
        if isinstance(input, KeyedStream):
            return input
        else:
            if key_selector is None:
                msg = "KeySelector must be provided."
                raise RuntimeError(msg)
            input = input.key_by(key_selector)
            return input

    def from_datastream(
        self, input: DataStream, key_selector: KeySelector = None
    ) -> RemoteAgentBuilder:
        """Set input datastream of agent.

        Parameters
        ----------
        input : DataStream
            Receive a DataStream as input.
        key_selector : KeySelector
            Extract key from each input record, must not be None when input is
            not KeyedStream.
        """
        input = self.__process_input_datastream(input, key_selector)

        return RemoteAgentBuilder(input=input)

    def from_table(
        self,
        input: Table,
        t_env: StreamTableEnvironment,
        key_selector: Optional[KeySelector] = None,
    ) -> AgentBuilder:
        """Set input Table of agent.

        Parameters
        ----------
        input : Table
            Receive a Table as input.
        t_env: StreamTableEnvironment
            table environment supports convert table to/from datastream.
        key_selector : KeySelector
            Extract key from each input record.
        """
        input = t_env.to_data_stream(table=input)

        input = input.map(lambda x: x, output_type=PickledBytesTypeInfo())

        input = self.__process_input_datastream(input, key_selector)
        return RemoteAgentBuilder(input=input, t_env=t_env)

    def from_list(self, input: List[Dict[str, Any]]) -> "AgentsExecutionEnvironment":
        """Set input list of agent execution.

        This method is not supported for remote execution environments.
        """
        msg = "RemoteExecutionEnvironment does not support from_list."
        raise NotImplementedError(msg)

    def execute(self) -> None:
        """Execute agent."""
        self.__env.execute()


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
    return RemoteExecutionEnvironment(env=env)
