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
import logging
import os
from pathlib import Path
from typing import Any, Callable, Dict, List

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
from flink_agents.api.execution_environment import (
    AgentBuilder,
    AgentsExecutionEnvironment,
)
from flink_agents.api.resource import ResourceType
from flink_agents.plan.agent_plan import AgentPlan
from flink_agents.plan.configuration import AgentConfiguration

_CONFIG_FILE_NAME = "config.yaml"
_LEGACY_CONFIG_FILE_NAME = "flink-conf.yaml"

class RemoteAgentBuilder(AgentBuilder):
    """RemoteAgentBuilder for integrating datastream/table and agent."""

    __input: DataStream
    __agent_plan: AgentPlan = None
    __output: DataStream = None
    __t_env: StreamTableEnvironment
    __config: AgentConfiguration
    __resources: Dict[ResourceType, Dict[str, Any]] = None

    def __init__(
        self,
        input: DataStream,
        config: AgentConfiguration,
        t_env: StreamTableEnvironment | None = None,
        resources: Dict[ResourceType, Dict[str, Any]] | None = None,
    ) -> None:
        """Init method of RemoteAgentBuilder."""
        self.__input = input
        self.__t_env = t_env
        self.__config = config
        self.__resources = resources

    @property
    def t_env(self) -> StreamTableEnvironment:
        """Get or crate table environment."""
        if self.__t_env is None:
            self.__t_env = StreamTableEnvironment.create(
                stream_execution_environment=self.__env
            )
        return self.__t_env

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
        for type, name_to_resource in self.__resources.items():
            agent.resources[type] = name_to_resource | agent.resources[type]

        self.__agent_plan = AgentPlan.from_agent(agent, self.__config)

        return self

    def to_datastream(self, output_type: TypeInformation | None = None) -> DataStream:
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
        return self.t_env.from_data_stream(self.to_datastream(output_type), schema)

    def to_list(self) -> List[Dict[str, Any]]:
        """Get output list of agent execution.

        This method is not supported for remote execution environments.
        """
        msg = "RemoteAgentBuilder does not support to_list."
        raise NotImplementedError(msg)


class RemoteExecutionEnvironment(AgentsExecutionEnvironment):
    """Implementation of AgentsExecutionEnvironment for execution with DataStream."""

    __env: StreamExecutionEnvironment
    __t_env: StreamTableEnvironment
    __config: AgentConfiguration

    def __init__(
        self,
        env: StreamExecutionEnvironment,
        t_env: StreamTableEnvironment | None = None,
    ) -> None:
        """Init method of RemoteExecutionEnvironment."""
        super().__init__()
        self.__env = env
        self.__t_env = t_env
        self.__config = AgentConfiguration()
        self.__load_config_from_flink_conf_dir()

    @property
    def t_env(self) -> StreamTableEnvironment:
        """Get or crate table environment."""
        if self.__t_env is None:
            self.__t_env = StreamTableEnvironment.create(
                stream_execution_environment=self.__env
            )
        return self.__t_env

    def get_config(self, path: str | None = None) -> AgentConfiguration:
        """Get the writable configuration for flink agents.

        Returns:
        -------
        LocalConfiguration
            The configuration for flink agents.
        """
        return self.__config

    @staticmethod
    def __process_input_datastream(
        input: DataStream, key_selector: KeySelector | Callable | None = None
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
        self, input: DataStream, key_selector: KeySelector | Callable | None = None
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

        return RemoteAgentBuilder(
            input=input,
            config=self.__config,
            t_env=self.__t_env,
            resources=self.resources,
        )

    def from_table(
        self,
        input: Table,
        key_selector: KeySelector | Callable | None = None,
    ) -> AgentBuilder:
        """Set input Table of agent.

        Parameters
        ----------
        input : Table
            Receive a Table as input.
        key_selector : KeySelector
            Extract key from each input record.
        """
        input = self.t_env.to_data_stream(table=input)

        input = input.map(lambda x: x, output_type=PickledBytesTypeInfo())

        input = self.__process_input_datastream(input, key_selector)
        return RemoteAgentBuilder(
            input=input,
            config=self.__config,
            t_env=self.t_env,
            resources=self.resources,
        )

    def from_list(self, input: List[Dict[str, Any]]) -> "AgentsExecutionEnvironment":
        """Set input list of agent execution.

        This method is not supported for remote execution environments.
        """
        msg = "RemoteExecutionEnvironment does not support from_list."
        raise NotImplementedError(msg)

    def execute(self) -> None:
        """Execute agent."""
        self.__env.execute()


    def __load_config_from_flink_conf_dir(self) -> None:
        """Load agent configuration from FLINK_CONF_DIR if available."""
        flink_conf_dir = os.environ.get("FLINK_CONF_DIR")
        if flink_conf_dir is None:
            return

        # Try to find config file, with fallback to legacy name
        config_path = self.__find_config_file(flink_conf_dir)

        if config_path is None:
            logging.error(f"Config file not found in {flink_conf_dir}")
        else:
            self.__config.load_from_file(str(config_path))

    def __find_config_file(self, flink_conf_dir: str) -> Path | None:
        """Find config file in the given directory, checking both new and legacy names.

        Parameters
        ----------
        flink_conf_dir : str
            Directory to search for config files.

        Returns:
        -------
        Path | None
            Path to the config file if found, None otherwise.
        """
        # Try legacy config file name first
        legacy_config_path = Path(flink_conf_dir).joinpath(_LEGACY_CONFIG_FILE_NAME)
        if legacy_config_path.exists():
            logging.warning(
                f"Using legacy config file {_LEGACY_CONFIG_FILE_NAME}"
            )
            return legacy_config_path

        # Try new config file name as fallback
        primary_config_path = Path(flink_conf_dir).joinpath(_CONFIG_FILE_NAME)
        if primary_config_path.exists():
            return primary_config_path

        return None


def create_instance(
    env: StreamExecutionEnvironment, t_env: StreamTableEnvironment, **kwargs: Any
) -> AgentsExecutionEnvironment:
    """Factory function to create a remote agents execution environment.

    Parameters
    ----------
    env : StreamExecutionEnvironment
        Flink job execution environment.
    t_env : StreamTableEnvironment
        Flink job execution table environment.
    **kwargs : Dict[str, Any]
        The dict of parameters to configure the execution environment.

    Returns:
    -------
    AgentsExecutionEnvironment
        A configured agents execution environment instance.
    """
    return RemoteExecutionEnvironment(env=env, t_env=t_env)
