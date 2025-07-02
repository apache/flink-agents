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
import uuid
from typing import Any, Dict, Generator, List

import cloudpickle
from pyflink.common import Row
from pyflink.common.typeinfo import PickledBytesTypeInfo, RowTypeInfo
from pyflink.datastream import (
    DataStream,
    KeyedProcessFunction,
    KeyedStream,
    KeySelector,
    StreamExecutionEnvironment,
)
from pyflink.util.java_utils import invoke_method

from flink_agents.api.event import InputEvent
from flink_agents.api.execution_enviroment import AgentsExecutionEnvironment
from flink_agents.api.workflow import Workflow
from flink_agents.plan.workflow_plan import WorkflowPlan


class MapKeyedProcessFunctionAdapter(KeyedProcessFunction):
    """Util class for converting element in KeyedStream to Row."""

    def process_element(
        self, value: Any, ctx: "KeyedProcessFunction.Context"
    ) -> Generator:
        """Convert element to Row contains key."""
        ctx.get_current_key()
        yield Row(ctx.get_current_key(), InputEvent(input=value))


class RemoteExecutionEnvironment(AgentsExecutionEnvironment):
    """Implementation of AgentsExecutionEnvironment for execution with DataStream."""

    __env: StreamExecutionEnvironment
    __input: DataStream
    __workflow_plan: WorkflowPlan
    __output: DataStream

    def from_datastream(
        self, input: DataStream, key_selector: KeySelector = None
    ) -> "AgentsExecutionEnvironment":
        """Set input for agents.

        Parameters
        ----------
        input : DataStream
            Receive a DataStream as input.
        key_selector : KeySelector
            Extract key from each input record.
        """
        if isinstance(input, KeyedStream):
            self.__input = input.process(
                MapKeyedProcessFunctionAdapter(),
                output_type=RowTypeInfo(
                    [PickledBytesTypeInfo(), PickledBytesTypeInfo()]
                ),
            )
        else:
            self.__input = input.map(
                lambda x: Row(
                    key_selector.get_key(x)
                    if key_selector is not None
                    else uuid.uuid4(),
                    InputEvent(input=x),
                ),
                output_type=RowTypeInfo(
                    [PickledBytesTypeInfo(), PickledBytesTypeInfo()]
                ),
            )
        return self

    def apply(self, workflow: Workflow) -> "AgentsExecutionEnvironment":
        """Set workflow of execution environment.

        Parameters
        ----------
        workflow : Workflow
            The workflow user defined to run in execution environment.
        """
        self.__workflow_plan = WorkflowPlan.from_workflow(workflow)
        return self

    def to_datastream(self) -> DataStream:
        """Get outputs of workflow execution. Used for remote execution.

        Returns:
        -------
        DataStream
            Outputs of workflow execution.
        """
        j_data_stream_output = invoke_method(
            None,
            "org.apache.flink.agents.runtime.FlinkAgent",
            "connectToWorkflow",
            [
                self.__input._j_data_stream,
                self.__workflow_plan.model_dump_json(serialize_as_any=True),
            ],
            [
                "org.apache.flink.streaming.api.datastream.DataStream",
                "java.lang.String",
            ],
        )
        output_stream = DataStream(j_data_stream_output)
        self.__output = output_stream.map(lambda x: cloudpickle.loads(x).output)
        return self.__output

    def execute(self) -> None:
        """Execute agents.

        This method is not supported for remote execution environments.
        """
        msg = "RemoteExecutionEnvironment does not support execute locally."
        raise NotImplementedError(msg)

    def from_list(self, input: List[Dict[str, Any]]) -> "AgentsExecutionEnvironment":
        """Set input list of workflow execution.

        This method is not supported for remote execution environments.
        """
        msg = "RemoteExecutionEnvironment does not support from_list."
        raise NotImplementedError(msg)

    def to_list(self) -> List[Dict[str, Any]]:
        """Get output list of workflow execution.

        This method is not supported for remote execution environments.
        """
        msg = "RemoteExecutionEnvironment does not support to_list."
        raise NotImplementedError(msg)


def get_execution_environment(**kwargs: Dict[str, Any]) -> AgentsExecutionEnvironment:
    """Factory function to create a remote agents execution environment.

    Parameters
    ----------
    **kwargs : Dict[str, Any]
        The dict of parameters to configure the execution environment.

    Returns:
    -------
    AgentsExecutionEnvironment
        A configured agents execution environment instance.
    """
    return RemoteExecutionEnvironment()
