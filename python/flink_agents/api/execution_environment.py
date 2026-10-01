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
from typing import TYPE_CHECKING, Any, Callable, Dict, List

if TYPE_CHECKING:
    from pathlib import Path

from importlib_resources import files
from pyflink.datastream import DataStream, KeySelector, StreamExecutionEnvironment
from pyflink.table import Schema, StreamTableEnvironment, Table

from flink_agents.api.agents.agent import Agent
from flink_agents.api.configuration import Configuration
from flink_agents.api.resource import (
    ResourceDescriptor,
    ResourceType,
    SerializableResource,
    check_registrable_from_python,
)
from flink_agents.api.version_compatibility import flink_version_manager


class AgentBuilder(ABC):
    """Builder for integrating agent with input and output."""

    @abstractmethod
    def apply(self, agent: "Agent | str") -> "AgentBuilder":
        """Set agent of AgentBuilder.

        Parameters
        ----------
        agent : Agent | str
            Either an Agent instance, or the name of an agent registered
            on the environment (e.g. by ``load_yaml``).
        """

    @abstractmethod
    def to_datastream(self, output_type: Any = None) -> DataStream:
        """Get the output datastream of agent execution.

        Without ``output_type`` the returned view is unrestricted: it carries
        whatever the agent emitted, with no output-type declaration. Passing
        ``output_type`` layers a downstream conversion operator that materializes
        each element as that declared type, leaving the unrestricted view
        unaffected, so several typed calls of different types can coexist on one
        execution.

        Parameters
        ----------
        output_type : Any
            Optional output-type declaration -- a Pydantic model / dataclass /
            named tuple / ``TypedDict`` / ``RowTypeInfo``, or an explicit
            ``TypeInformation``. When omitted, the unrestricted view is returned.

        Returns:
        -------
        DataStream
            Output datastream of agent execution.
        """

    @abstractmethod
    def to_table(self, schema: Schema | None = None, output_type: Any = None) -> Table:
        """Get output table of agent execution.

        At least one of ``schema`` or ``output_type`` must be given. A ``schema``
        fixes the physical columns and may carry Table-domain information such as
        a primary key, computed columns, or a watermark; an ``output_type``
        derives the physical schema from the declared type (for example a POJO's
        fields become columns). Passing both cross-checks that they describe the
        same row type.

        Parameters
        ----------
        schema : Schema | None
            A Table ``Schema`` whose physical columns give the row type.
        output_type : Any
            The output-type declaration -- a Pydantic model / dataclass / named
            tuple / ``TypedDict`` / ``RowTypeInfo``, or an explicit
            ``TypeInformation``.

        Returns:
        -------
        Table
            Output table of agent execution.
        """


class AgentsExecutionEnvironment(ABC):
    """Base class for agent execution environment."""

    _resources: Dict[ResourceType, Dict[str, Any]]
    _agents: Dict[str, Agent]

    def __init__(self) -> None:
        """Init method."""
        self._actions = {}
        self._resources = {}
        for type in ResourceType:
            self._resources[type] = {}
        self._agents: Dict[str, Agent] = {}

    @property
    def resources(self) -> Dict[ResourceType, Dict[str, Any]]:
        """Get registered resources."""
        return self._resources

    @staticmethod
    def get_execution_environment(
        env: StreamExecutionEnvironment | None = None,
        t_env: StreamTableEnvironment | None = None,
        **kwargs: Dict[str, Any],
    ) -> "AgentsExecutionEnvironment":
        """Get agents execution environment.

        A Flink ``StreamExecutionEnvironment`` is required. When running flink agents
        with pyflink datastream/table, pass the ``StreamExecutionEnvironment`` so the
        agents run on the Flink runtime.

        Parameters
        ----------
        env : StreamExecutionEnvironment
            The Flink stream execution environment the agents run on. Must not be None.

        Returns:
        -------
        AgentsExecutionEnvironment
            Environment for agent execution.
        """
        if env is None:
            err_msg = "A StreamExecutionEnvironment is required."
            raise ValueError(err_msg)

        major_version = flink_version_manager.major_version
        if not major_version:
            err_msg = "Apache Flink is not installed."
            raise ModuleNotFoundError(err_msg)

        lib_base = files("flink_agents.lib")

        # Load the common JAR (shared dependencies)
        common_lib = lib_base / "common"
        if common_lib.is_dir():
            for jar_file in common_lib.iterdir():
                if jar_file.is_file() and str(jar_file).endswith(".jar"):
                    env.add_jars(jar_file.resolve().as_uri())
        else:
            err_msg = "Flink Agents common JAR not found."
            raise FileNotFoundError(err_msg)

        # Load the version-specific thin JAR
        version_dir = f"flink-{major_version}"
        version_lib = lib_base / version_dir

        # Check if version-specific directory exists
        if version_lib.is_dir():
            for jar_file in version_lib.iterdir():
                if jar_file.is_file() and str(jar_file).endswith(".jar"):
                    env.add_jars(jar_file.resolve().as_uri())
        else:
            err_msg = f"Flink Agents dist JAR for Flink {major_version} not found."
            raise FileNotFoundError(err_msg)

        return importlib.import_module(
            "flink_agents.runtime.remote_execution_environment"
        ).create_instance(env=env, t_env=t_env, **kwargs)

    @abstractmethod
    def get_config(self, path: str | None = None) -> Configuration:
        """Get the writable configuration for flink agents.

        Returns:
        -------
        WritableConfiguration
            The configuration for flink agents.
        """

    @abstractmethod
    def from_datastream(
        self, input: DataStream, key_selector: KeySelector | Callable | None = None
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
        key_selector: KeySelector | Callable | None = None,
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
    def execute(self, job_name: str | None = None) -> None:
        """Execute agent individually."""

    def add_resource(
        self,
        name: str,
        resource_type: ResourceType,
        instance: SerializableResource | ResourceDescriptor,
    ) -> "AgentsExecutionEnvironment":
        """Register resource to agent execution environment.

        Parameters
        ----------
        name : str
            The name of the prompt, should be unique in the same Agent.
        resource_type: ResourceType
            The type of the resource.
        instance: SerializableResource | ResourceDescriptor
            The serializable resource instance, or the descriptor of resource.

        Returns:
        -------
        AgentsExecutionEnvironment
            The environment to register the resource.
        """
        check_registrable_from_python(resource_type)
        if name in self._resources[resource_type]:
            msg = f"{resource_type.value} {name} already defined"
            raise ValueError(msg)

        self._resources[resource_type][name] = instance
        return self

    def load_yaml(self, paths: "Path | str | List[Path | str]") -> None:
        """Load one or more YAML files and register their declared agents
        and shared resources on this environment.

        See :mod:`flink_agents.api.yaml.loader` for the format reference.
        """
        from flink_agents.api.yaml.loader import load_yaml as _load_yaml

        _load_yaml(self, paths)
