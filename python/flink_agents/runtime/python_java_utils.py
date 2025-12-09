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
from typing import Any

import cloudpickle

from flink_agents.api.events.event import InputEvent


def convert_to_python_object(bytesObject: bytes) -> Any:
    """Used for deserializing Python objects."""
    return cloudpickle.loads(bytesObject)


def wrap_to_input_event(bytesObject: bytes) -> tuple[bytes, str]:
    """Wrap data to python input event and serialize.

    Returns:
        A tuple of (serialized_event_bytes, event_string_representation)
    """
    event = InputEvent(input=cloudpickle.loads(bytesObject))
    return (cloudpickle.dumps(event), str(event))


def get_output_from_output_event(bytesObject: bytes) -> Any:
    """Get output data from OutputEvent and serialize."""
    return cloudpickle.dumps(convert_to_python_object(bytesObject).output)
