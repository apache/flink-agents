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

from typing import Dict, List, Type

from pydantic import BaseModel

from flink_agents.api.event import Event
from flink_agents.plan.action import Action


class WorkflowPlan(BaseModel):
    """Workflow plan compiled from user defined workflow.

    Attributes:
    ----------
    actions : Dict[Type[Event], List[Action]]
        Mapping of event types to the list of Actions that listen to them.
    """

    actions: Dict[Type[Event], List[Action]]

    def get_actions(self, event_type: Type[Event]) -> List[Action]:
        """Get actions that listen to the specified event type.

        Parameters
        ----------
        event_type : Type[Event]
            The event type to query.

        Returns:
        -------
        list[Action]
            List of Actions that will respond to this event type.
        """
        return self.actions[event_type]
