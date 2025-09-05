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

from pyflink.common.typeinfo import BasicTypeInfo, RowTypeInfo

from flink_agents.api.agents.react_agent import OutputSchema


def test_output_schema_serializable() -> None:  # noqa: D103
    schema = OutputSchema(
        output_schema=RowTypeInfo(
            [BasicTypeInfo.INT_TYPE_INFO()],
            ["result"],
        )
    )

    json_data = schema.model_dump_json()

    deserialize_schema = OutputSchema.model_validate_json(json_data)
    assert schema == deserialize_schema
