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
from typing import Any, Callable, Dict, List, Type

from pyflink.common import TypeInformation
from pyflink.datastream import DataStream, KeySelector, StreamExecutionEnvironment
from pyflink.table import Schema, StreamTableEnvironment, Table

from flink_agents.api.agent import Agent
from flink_agents.api.chat_models.chat_model import (
    BaseChatModelConnection,
    BaseChatModelSetup,
)
from flink_agents.api.configuration import Configuration
from flink_agents.api.embedding_models.embedding_model import (
    BaseEmbeddingModelConnection,
    BaseEmbeddingModelSetup,
)
from flink_agents.api.prompts.prompt import Prompt
from flink_agents.api.resource import ResourceType
from flink_agents.api.tools.mcp import MCPServer
from flink_agents.api.vector_stores.vector_store import (
    BaseVectorStoreConnection,
    BaseVectorStoreSetup,
)


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

    _resources: Dict[ResourceType, Dict[str, Any]]

    def __init__(self) -> None:
        """Init method."""
        self._actions = {}
        self._resources = {}
        for type in ResourceType:
            self._resources[type] = {}

    @property
    def resources(self) -> Dict[ResourceType, Dict[str, Any]]:
        """Get registered resources."""
        return self._resources

    @staticmethod
    def get_execution_environment(
        env: StreamExecutionEnvironment | None = None, **kwargs: Dict[str, Any]
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
    def get_config(self, path: str | None = None) -> Configuration:
        """Get the writable configuration for flink agents.

        Returns:
        -------
        WritableConfiguration
            The configuration for flink agents.
        """

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
        self, input: DataStream, key_selector: KeySelector | None = None
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
        key_selector: KeySelector | None = None,
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

    def add_prompt(self, name: str, prompt: Prompt) -> "AgentsExecutionEnvironment":
        """Register prompt to agent execution environment.

        Parameters
        ----------
        name : str
            The name of the prompt, should be unique in the same Agent.
        prompt: Prompt
            The prompt to be used in the agent.

        Returns:
        -------
        AgentsExecutionEnvironment
            The environment contains registered prompt.
        """
        if name in self._resources[ResourceType.PROMPT]:
            msg = f"Prompt {name} already defined"
            raise ValueError(msg)
        self._resources[ResourceType.PROMPT][name] = prompt
        return self

    def add_tool(self, name: str, func: Callable) -> "AgentsExecutionEnvironment":
        """Register function tool to agent execution environment.

        Parameters
        ----------
        name : str
            The name of the tool, should be unique in the same Agent.
        func: Callable
            The execution function of the tool.

        Returns:
        -------
        AgentsExecutionEnvironment
            The environment contains registered tool.
        """
        if name in self._resources[ResourceType.TOOL]:
            msg = f"Function tool {name} already defined"
            raise ValueError(msg)
        self._resources[ResourceType.TOOL][name] = func
        return self

    def add_chat_model_connection(
        self, name: str, connection: Type[BaseChatModelConnection], **kwargs: Any
    ) -> "AgentsExecutionEnvironment":
        """Register chat model connection to agent execution environment.

        Parameters
        ----------
        name : str
            The name of the chat model connection, should be unique in the same Agent.
        connection: Type[BaseChatModelConnection]
            The type of chat model connection.
        **kwargs: Any
            Initialize keyword arguments passed to the chat model connection.

        Returns:
        -------
        AgentsExecutionEnvironment
            The environment contains registered chat model connection.
        """
        if name in self._resources[ResourceType.CHAT_MODEL_CONNECTION]:
            msg = f"Chat model connection {name} already defined"
            raise ValueError(msg)
        kwargs["name"] = name
        self._resources[ResourceType.CHAT_MODEL_CONNECTION][name] = (connection, kwargs)
        return self

    def add_chat_model_setup(
        self, name: str, chat_model: Type[BaseChatModelSetup], **kwargs: Any
    ) -> "AgentsExecutionEnvironment":
        """Register chat model setup to agent execution environment.

        Parameters
        ----------
        name : str
            The name of the chat model, should be unique in the same Agent.
        chat_model: Type[BaseChatModel]
            The type of chat model.
        **kwargs: Any
            Initialize keyword arguments passed to the chat model.

        Returns:
        -------
        AgentsExecutionEnvironment
            The environment contains registered chat model setup.
        """
        if name in self._resources[ResourceType.CHAT_MODEL]:
            msg = f"Chat model setup {name} already defined"
            raise ValueError(msg)
        kwargs["name"] = name
        self._resources[ResourceType.CHAT_MODEL][name] = (chat_model, kwargs)
        return self

    def add_embedding_model_connection(
        self, name: str, connection: Type[BaseEmbeddingModelConnection], **kwargs: Any
    ) -> "AgentsExecutionEnvironment":
        """Register embedding model connection to agent execution environment.

        Parameters
        ----------
        name : str
            The name of the embedding model connection, should be unique in the same
            Agent.
        connection: Type[BaseEmbeddingModelConnection]
            The type of embedding model connection.
        **kwargs: Any
            Initialize keyword arguments passed to the embedding model connection.

        Returns:
        -------
        AgentsExecutionEnvironment
            The environment contains registered embedding model connection.
        """
        if name in self._resources[ResourceType.EMBEDDING_MODEL_CONNECTION]:
            msg = f"Embedding model connection {name} already defined"
            raise ValueError(msg)
        kwargs["name"] = name
        self._resources[ResourceType.EMBEDDING_MODEL_CONNECTION][name] = (connection, kwargs)
        return self

    def add_embedding_model_setup(
        self, name: str, embedding_model: Type[BaseEmbeddingModelSetup], **kwargs: Any
    ) -> "AgentsExecutionEnvironment":
        """Register embedding model setup to agent execution environment.

        Parameters
        ----------
        name : str
            The name of the embedding model, should be unique in the same Agent.
        embedding_model: Type[BaseEmbeddingModelSetup]
            The type of embedding model.
        **kwargs: Any
            Initialize keyword arguments passed to the embedding model.

        Returns:
        -------
        AgentsExecutionEnvironment
            The environment contains registered embedding model setup.
        """
        if name in self._resources[ResourceType.EMBEDDING_MODEL]:
            msg = f"Embedding model setup {name} already defined"
            raise ValueError(msg)
        kwargs["name"] = name
        self._resources[ResourceType.EMBEDDING_MODEL][name] = (embedding_model, kwargs)
        return self

    def add_mcp_server(self, name: str, mcp_server: MCPServer) -> "AgentsExecutionEnvironment":
        """Add an MCP server to the agent execution environment.

        Parameters
        ----------
        name : str
            The name of the MCP server, should be unique in the same Agent.
        mcp_server : MCPServer
            The MCP server resource instance.

        Returns:
        -------
        AgentsExecutionEnvironment
            The environment contains registered embedding model setup.
        """
        if name in self._resources[ResourceType.MCP_SERVER]:
            msg = f"MCP server {name} already defined"
            raise ValueError(msg)
        self._resources[ResourceType.MCP_SERVER][name] = mcp_server
        return self

    def add_vector_store_connection(
            self, name: str, connection: Type[BaseVectorStoreConnection], **kwargs: Any
    ) -> "AgentsExecutionEnvironment":
        """Register vector store connection to agent execution environment.
        Parameters
        ----------
        name : str
            The name of the vector store connection, should be unique in the same
            Agent.
        connection: Type[BaseVectorStoreConnection]
            The type of vector store connection.
        **kwargs: Any
            Initialize keyword arguments passed to the vector store connection.
        Returns:
        -------
        AgentsExecutionEnvironment
            The environment contains registered vector store connection.
        """
        if name in self._resources[ResourceType.VECTOR_STORE_CONNECTION]:
            msg = f"Vector store connection {name} already defined"
            raise ValueError(msg)
        kwargs["name"] = name
        self._resources[ResourceType.VECTOR_STORE_CONNECTION][name] = (connection, kwargs)
        return self

    def add_vector_store_setup(
            self, name: str, vector_store: Type[BaseVectorStoreSetup], **kwargs: Any
    ) -> "AgentsExecutionEnvironment":
        """Register vector store setup to agent execution environment.
        Parameters
        ----------
        name : str
            The name of the vector store, should be unique in the same Agent.
        vector_store: Type[BaseVectorStoreSetup]
            The type of vector store.
        **kwargs: Any
            Initialize keyword arguments passed to the vector store.
        Returns:
        -------
        AgentsExecutionEnvironment
            The environment contains registered vector store setup.
        """
        if name in self._resources[ResourceType.VECTOR_STORE]:
            msg = f"Vector store setup {name} already defined"
            raise ValueError(msg)
        kwargs["name"] = name
        self._resources[ResourceType.VECTOR_STORE][name] = (vector_store, kwargs)
        return self
