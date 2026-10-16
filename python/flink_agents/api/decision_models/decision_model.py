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
################################################################################
"""Resources for making typed decisions from application-supplied evidence."""

from abc import ABC, abstractmethod
from typing import Any, Dict

from pydantic import ConfigDict, Field, field_validator
from typing_extensions import override

from flink_agents.api.decision_models.decision_request import DecisionRequest
from flink_agents.api.decision_models.decision_result import DecisionResult
from flink_agents.api.resource import Resource, ResourceType


class BaseDecisionModelConnection(Resource, ABC):
    """Connection shared by decision model setups.

    A connection owns the provider client and its authentication and transport
    settings. Use open and close to manage the client lifecycle.
    """

    model_config = ConfigDict(extra="forbid", allow_inf_nan=False)

    @classmethod
    @override
    def resource_type(cls) -> ResourceType:
        """Return the connection resource type."""
        return ResourceType.DECISION_MODEL_CONNECTION

    @abstractmethod
    def decide(self, request: DecisionRequest, *, model: str) -> DecisionResult:
        """Evaluate all questions with the selected model.

        Return one answer per question, preserving identifiers, distributions and
        explicit refusals. Provider request errors and malformed answers raise
        exceptions. This call does not apply business thresholds or routing.
        """


class BaseDecisionModelSetup(Resource):
    """A decision model configured with a connection and a model name.

    Registered setups resolve named connections through the ResourceContext.
    For direct use, pass a connection instance and manage its lifecycle yourself.
    Closing a setup does not close a shared connection.
    """

    model_config = ConfigDict(extra="forbid", allow_inf_nan=False)

    connection: str | BaseDecisionModelConnection = Field(
        description="Connection resource name or a connection instance."
    )
    model: str = Field(description="Provider model name.")

    @field_validator("model")
    @classmethod
    def _validate_model(cls, value: str) -> str:
        if not value.strip():
            msg = "Model must not be blank."
            raise ValueError(msg)
        return value

    @field_validator("connection")
    @classmethod
    def _validate_connection(
        cls, value: str | BaseDecisionModelConnection
    ) -> str | BaseDecisionModelConnection:
        if isinstance(value, str) and not value.strip():
            msg = "Connection name must not be blank."
            raise ValueError(msg)
        return value

    @classmethod
    @override
    def resource_type(cls) -> ResourceType:
        """Return the model setup resource type."""
        return ResourceType.DECISION_MODEL

    @property
    def model_kwargs(self) -> Dict[str, Any]:
        """Return the parameters supplied to the provider connection."""
        return {"model": self.model}

    @override
    def open(self) -> None:
        if isinstance(self.connection, str):
            if self.resource_context is None:
                msg = "A named decision connection requires a ResourceContext."
                raise RuntimeError(msg)
            connection = self.resource_context.get_resource(
                self.connection, ResourceType.DECISION_MODEL_CONNECTION
            )
            if not isinstance(connection, BaseDecisionModelConnection):
                msg = "The referenced resource must be a decision model connection."
                raise TypeError(msg)
            self.connection = connection

    def decide(self, request: DecisionRequest) -> DecisionResult:
        """Evaluate the supplied questions using this setup's model.

        Questions are supplied for every call, so one model setup can serve
        multiple business actions with different policies and evidence.
        """
        if isinstance(self.connection, str):
            msg = "Open the setup to resolve its named decision connection before calling decide."
            raise TypeError(msg)
        return self.connection.decide(request, **self.model_kwargs)
