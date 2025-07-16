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
import json
from pathlib import Path

import pytest

from flink_agents.api.resource import Resource, ResourceProvider, ResourceType
from flink_agents.plan.resource_provider import PythonResourceProvider

current_dir = Path(__file__).parent

class MockResourceImpl(Resource): # noqa: D101
    host: str
    desc: str

    @classmethod
    def resource_type(cls) -> ResourceType: # noqa: D102
        return ResourceType.CHAT_MODEL

@pytest.fixture(scope="module")
def resource_provider() -> ResourceProvider:  # noqa: D103
    return PythonResourceProvider(name="mock", type=MockResourceImpl.resource_type(), module=MockResourceImpl.__module__,
                            clazz=MockResourceImpl.__name__, kwargs={"host":"8.8.8.8", "desc":"mock chat model"})

def test_python_resource_provider_serialize(resource_provider: ResourceProvider) -> None: # noqa: D103
    json_value = resource_provider.model_dump_json(serialize_as_any=True)
    with Path.open(Path(f'{current_dir}/resources/resource_provider.json')) as f:
        expected_json = f.read()
    actual = json.loads(json_value)
    expected = json.loads(expected_json)
    assert actual == expected

def test_python_resource_provider_deserialize(resource_provider: ResourceProvider) -> None: # noqa: D103
    with Path.open(Path(f'{current_dir}/resources/resource_provider.json')) as f:
        expected_json = f.read()
    expected_resource_provider = PythonResourceProvider.model_validate_json(expected_json)
    assert resource_provider == expected_resource_provider

