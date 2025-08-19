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

from pyflink.java_gateway import get_gateway

from flink_agents.api.configuration import ConfigOption


def covert_j_option_to_python_option(j_option: Any) -> ConfigOption:
    """Convert a Java config option to a Python config option."""
    key = j_option.getKey()
    default = j_option.getDefaultValue()
    type_name = j_option.getTypeName()

    if type_name == "java.lang.String":
        config_type = str
    elif type_name == "java.lang.Integer":
        config_type = int
    elif type_name == "java.lang.Long":
        config_type = int
    elif type_name == "java.lang.Boolean":
        config_type = bool
    elif type_name == "java.lang.Float":
        config_type = float
    elif type_name == "java.lang.Double":
        config_type = float
    else:
        msg = f"Unsupported type: {type_name}"
        raise TypeError(msg)

    return ConfigOption(key, config_type, default)


class AgentConfigOptionsMeta(type):
    """Metaclass for FlinkAgentsCoreOptions."""
    def __init__(cls, name: str, bases: tuple[type, ...], attrs: dict[str, Any]) -> None:
        """Initialize the metaclass for FlinkAgentsCoreOptions."""
        super().__init__(name, bases, attrs)

        jvm = get_gateway().jvm
        cls.jvm = jvm

    def __getattr__(cls, item: str) -> ConfigOption:
        j_option = getattr(
            cls.jvm.org.apache.flink.agents.api.configuration.AgentConfigOptions,
            item,
        )

        python_option = covert_j_option_to_python_option(j_option)
        return python_option


class AgentConfigOptions(metaclass=AgentConfigOptionsMeta):
    """CoreOptions to manage core configuration parameters for Flink Agents."""
