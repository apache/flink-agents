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
from pathlib import Path

from pyflink.java_gateway import get_gateway
from pyflink.util.java_utils import add_jars_to_context_class_loader

from flink_agents.api.core_options import AgentConfigOptions, AgentExecutionOptions
from flink_agents.plan.tests.compatibility.java_python_config_option_parity import (
    assert_options_class_matches_java,
)

# Client-side parity check: loads the Java API JAR into a PyFlink gateway process and
# compares each explicitly declared Python ConfigOption against the Java definition.
# This script is invoked by test_java_config_in_python.sh (not inside the Pemja worker),
# so using get_gateway() here is intentional and separate from runtime import safety.
#
# The JAR file path is relative to this script and should be updated if the build
# structure changes.
if __name__ == "__main__":
    current_dir = Path(__file__).parent

    jars = Path(current_dir).glob("../../../../../api/target/flink-agents-api-*.jar")
    jar_urls = [f"file:///{jar.resolve()}" for jar in jars]
    add_jars_to_context_class_loader(jar_urls)

    jvm = get_gateway().jvm
    class_loader = jvm.java.lang.Thread.currentThread().getContextClassLoader()
    java_agent_config_options = class_loader.loadClass(
        "org.apache.flink.agents.api.configuration.AgentConfigOptions"
    )
    java_agent_execution_options = class_loader.loadClass(
        "org.apache.flink.agents.api.agents.AgentExecutionOptions"
    )

    assert_options_class_matches_java(
        AgentConfigOptions, java_agent_config_options, jvm
    )
    assert_options_class_matches_java(
        AgentExecutionOptions, java_agent_execution_options, jvm
    )
