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
from typing import Any, Callable, Dict

import cloudpickle
from pyflink.common.typeinfo import (
    ExternalTypeInfo,
    PickledBytesTypeInfo,
    RowTypeInfo,
)
from pyflink.datastream import (
    DataStream,
    KeyedStream,
    KeySelector,
    StreamExecutionEnvironment,
)
from pyflink.table import Schema, StreamTableEnvironment, Table
from pyflink.util.java_utils import invoke_method

from flink_agents.api.agents.agent import Agent
from flink_agents.api.execution_environment import (
    AgentBuilder,
    AgentsExecutionEnvironment,
)
from flink_agents.api.resource import ResourceType
from flink_agents.plan.agent_plan import AgentPlan
from flink_agents.plan.configuration import AgentConfiguration
from flink_agents.runtime.output_type_utils import (
    _unwrap_type_info,
    is_structured_declaration,
    reconstruct_instance,
    resolve_row_type_info,
    row_shape,
    row_type_info_to_schema,
    schema_to_row_type_info,
    to_row,
)

_CONFIG_FILE_NAME = "config.yaml"
_LEGACY_CONFIG_FILE_NAME = "flink-conf.yaml"
_AGENT_PLAN_JSON_VALIDATOR_CLASS = "org.apache.flink.agents.plan.AgentPlanJsonValidator"
_AGENT_PLAN_JSON_VALIDATOR_METHOD = "validateAgentPlan"


def _row_types_equal(left: RowTypeInfo, right: RowTypeInfo) -> bool:
    """Whether two row types describe the same fields (names and types).

    Java ``RowTypeInfo.equals`` ignores field names, so names are compared
    separately while the Java check covers the (possibly nested) field types.
    """
    return list(left.get_field_names()) == list(right.get_field_names()) and bool(
        left.get_java_type_info().equals(right.get_java_type_info())
    )


class RemoteAgentBuilder(AgentBuilder):
    """RemoteAgentBuilder for integrating datastream/table and agent."""

    __input: DataStream
    __agent_plan_json: str | None = None
    __raw_output: DataStream = None
    __t_env: StreamTableEnvironment
    __config: AgentConfiguration
    __resources: Dict[ResourceType, Dict[str, Any]] = None
    __agents: Dict[str, Agent]

    def __init__(
        self,
        input: DataStream,
        config: AgentConfiguration,
        t_env: StreamTableEnvironment | None = None,
        resources: Dict[ResourceType, Dict[str, Any]] | None = None,
        agents: Dict[str, Agent] | None = None,
    ) -> None:
        """Init method of RemoteAgentBuilder."""
        self.__input = input
        self.__t_env = t_env
        self.__config = config
        self.__resources = resources
        self.__agents = agents or {}

    @property
    def t_env(self) -> StreamTableEnvironment:
        """Get or crate table environment."""
        if self.__t_env is None:
            self.__t_env = StreamTableEnvironment.create(
                stream_execution_environment=self.__env
            )
        return self.__t_env

    def apply(self, agent: Agent | str) -> "AgentBuilder":
        """Set agent of execution environment.

        Parameters
        ----------
        agent : Agent | str
            Either an Agent instance, or the name of an agent registered
            on the environment (e.g. by ``load_yaml``).
        """
        if self.__agent_plan_json is not None:
            err_msg = "RemoteAgentBuilder doesn't support apply multiple agents yet."
            raise RuntimeError(err_msg)
        agent_name = None
        if isinstance(agent, str):
            agent_name = agent
            if agent not in self.__agents:
                msg = (
                    f"No agent named {agent!r} is registered on this "
                    "environment. Did you call load_yaml first?"
                )
                raise ValueError(msg)
            agent = self.__agents[agent]

        # inspect refer actions and resources from env to agent.
        for type, name_to_resource in self.__resources.items():
            agent.resources[type] = name_to_resource | agent.resources[type]

        agent_plan_json = AgentPlan.from_agent(
            agent, self.__config, agent_name
        ).model_dump_json(serialize_as_any=True)
        self.__validate_agent_plan_json(agent_plan_json)
        self.__agent_plan_json = agent_plan_json

        return self

    @staticmethod
    def __validate_agent_plan_json(agent_plan_json: str) -> None:
        try:
            error_message = invoke_method(
                None,
                _AGENT_PLAN_JSON_VALIDATOR_CLASS,
                _AGENT_PLAN_JSON_VALIDATOR_METHOD,
                [agent_plan_json],
                ["java.lang.String"],
            )
        except Exception as error:
            message = "Java AgentPlan JSON validation failed."
            raise RuntimeError(message) from error

        if error_message is not None:
            raise ValueError(error_message)

    def _raw_output_stream(self) -> DataStream:
        """Return the shared, untyped agent output stream (pickled bytes).

        Every terminal layers its own conversion operator on this single cached
        stream, so requesting a typed view never re-runs the agent operator and
        never changes the element type of the unrestricted view. Caching here, at
        the untyped boundary, is what removes the previous bug where the first
        requested ``output_type`` was silently reused by every later terminal.
        """
        if self.__agent_plan_json is None:
            err_msg = "Must apply agent before call to_datastream/to_table."
            raise RuntimeError(err_msg)
        if self.__raw_output is None:
            j_data_stream_output = invoke_method(
                None,
                "org.apache.flink.agents.runtime.CompileUtils",
                "connectToAgent",
                [
                    self.__input._j_data_stream,
                    self.__agent_plan_json,
                ],
                [
                    "org.apache.flink.streaming.api.datastream.KeyedStream",
                    "java.lang.String",
                ],
            )
            self.__raw_output = DataStream(j_data_stream_output)
        return self.__raw_output

    def _row_stream(self, row_type_info: RowTypeInfo) -> DataStream:
        """Conversion operator adapting the raw output to physical ``Row``s.

        Only the picklable nested field-name shape is captured in the closure;
        the py4j-backed ``RowTypeInfo`` stays on the driver and is handed to the
        operator as ``ExternalTypeInfo`` for the Table/Row serializer.
        """
        raw = self._raw_output_stream()
        shape = row_shape(row_type_info)
        return raw.map(
            lambda b: to_row(cloudpickle.loads(b), shape),
            output_type=ExternalTypeInfo(row_type_info),
        )

    def to_datastream(self, output_type: Any = None) -> DataStream:
        """Get the output datastream of agent execution.

        The typed view is a downstream conversion operator on the shared, cached
        raw stream, so it never re-runs the agent operator nor changes the element
        type of the unrestricted view, and several typed views of different types
        can coexist on one execution.

        Parameters
        ----------
        output_type : Any
            Optional output-type declaration. When omitted, the unrestricted view
            carries whatever the agent emitted; when given, a downstream conversion
            operator materializes each element as that type.

        Returns:
        -------
        DataStream
            Output datastream of agent execution.
        """
        raw = self._raw_output_stream()
        if output_type is None:
            # Unrestricted view: keep the agent's own output elements.
            return raw.map(lambda b: cloudpickle.loads(b))
        if is_structured_declaration(output_type):
            # Typed view of a python structured type: rebuild declared instances.
            # A Pydantic model has no native Flink TypeInformation, so the stream
            # stays pickle-typed and the increment is the validated instances.
            model_cls = output_type
            return raw.map(
                lambda b: reconstruct_instance(model_cls, cloudpickle.loads(b))
            )
        declared = _unwrap_type_info(output_type)
        if isinstance(declared, RowTypeInfo):
            return self._row_stream(declared)
        # Scalar / other explicit TypeInformation: elements already match the type.
        return raw.map(lambda b: cloudpickle.loads(b), output_type=output_type)

    def to_table(self, schema: Schema | None = None, output_type: Any = None) -> Table:
        """Get output Table of agent execution.

        At least one of ``schema`` or ``output_type`` must be given; passing both
        cross-checks that they describe the same row type.

        Parameters
        ----------
        schema : Schema | None
            A Table ``Schema`` whose physical columns give the row type. It may
            also carry Table-domain information such as a primary key, computed
            columns, or a watermark.
        output_type : Any
            The output-type declaration -- a Pydantic model / dataclass / named
            tuple / ``TypedDict`` / ``RowTypeInfo``, or an explicit
            ``TypeInformation``. The physical schema is derived from it.

        Returns:
        -------
        Table
            Output Table of agent execution.
        """
        if schema is not None and not isinstance(schema, Schema):
            msg = (
                "to_table 'schema' accepts a Table Schema only; to declare the "
                "output with a type (Pydantic model / dataclass / named tuple / "
                "TypedDict / RowTypeInfo), pass it as output_type=... instead."
            )
            raise TypeError(msg)
        if isinstance(output_type, Schema):
            msg = (
                "to_table 'output_type' accepts a type declaration (Pydantic model "
                "/ dataclass / named tuple / TypedDict / RowTypeInfo / "
                "TypeInformation); pass a Table Schema as schema=... instead."
            )
            raise TypeError(msg)
        if schema is None and output_type is None:
            msg = (
                "to_table requires at least one of 'schema' or 'output_type'; "
                "neither was given."
            )
            raise ValueError(msg)
        schema_row_type = (
            schema_to_row_type_info(schema) if schema is not None else None
        )
        output_row_type = (
            None if output_type is None else resolve_row_type_info(output_type)
        )
        if schema_row_type is not None and output_row_type is not None:
            if not _row_types_equal(schema_row_type, output_row_type):
                msg = (
                    "to_table got a schema and an output type describing different "
                    f"row types: {schema_row_type!r} vs {output_row_type!r}"
                )
                raise ValueError(msg)
        row_type_info = schema_row_type or output_row_type

        # Preserve the caller's Schema when present so Table-domain information
        # (primary key, computed columns, watermark) survives; otherwise derive it.
        table_schema = (
            schema if schema is not None else row_type_info_to_schema(row_type_info)
        )
        return self.t_env.from_data_stream(
            self._row_stream(row_type_info), table_schema
        )


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
            agents=self._agents,
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
            agents=self._agents,
        )

    def execute(self, job_name: str | None = None) -> None:
        """Execute agent."""
        self.__env.execute(job_name=job_name)

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
            logging.warning(f"Using legacy config file {_LEGACY_CONFIG_FILE_NAME}")
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
