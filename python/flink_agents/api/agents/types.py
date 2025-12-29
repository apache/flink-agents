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
from typing import Any

from pydantic import BaseModel, ConfigDict, model_serializer, model_validator
from pyflink.common.typeinfo import BasicType, BasicTypeInfo, RowTypeInfo


class OutputSchema(BaseModel):
    """Util class to help serialize and deserialize output schema json."""

    model_config = ConfigDict(arbitrary_types_allowed=True)
    output_schema: type[BaseModel] | RowTypeInfo

    @model_serializer
    def __custom_serializer(self) -> dict[str, Any]:
        if isinstance(self.output_schema, RowTypeInfo):
            data = {
                "output_schema": {
                    "names": self.output_schema.get_field_names(),
                    "types": [
                        type._basic_type.value
                        for type in self.output_schema.get_field_types()
                    ],
                },
            }
        else:
            data = {
                "output_schema": {
                    "module": self.output_schema.__module__,
                    "class": self.output_schema.__name__,
                }
            }
        return data

    @model_validator(mode="before")
    def __custom_deserialize(self) -> "OutputSchema":
        output_schema = self["output_schema"]
        if isinstance(output_schema, dict):
            if "names" in output_schema:
                self["output_schema"] = RowTypeInfo(
                    field_types=[
                        BasicTypeInfo(BasicType(type))
                        for type in output_schema["types"]
                    ],
                    field_names=output_schema["names"],
                )
            else:
                module = importlib.import_module(output_schema["module"])
                self["output_schema"] = getattr(module, output_schema["class"])
        return self
