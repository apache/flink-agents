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

from pyflink.util.java_utils import add_jars_to_context_class_loader

from flink_agents.api.core_options import AgentConfigOptions

# This script is used to verify that Java-defined configuration options
# (e.g., AgentConfigOptions) are correctly exposed and accessible in the
# Python environment via the JAR file. It loads a Java JAR into the Python
# context and performs basic assertions on the configuration keys, types,
# and default values to ensure compatibility between Java and Python layers.
#
# The JAR file path is relative to this script and should be updated if
# the build structure changes.
if __name__ == "__main__":
    current_dir = Path(__file__).parent

    jars = Path(current_dir).glob("../../../../../api/target/flink-agents-api-*.jar")
    jars = [f"file:///{jar}" for jar in jars]
    add_jars_to_context_class_loader(jars)

    assert AgentConfigOptions.BASE_LOG_DIR.get_key() == "baseLogDir"
    assert AgentConfigOptions.BASE_LOG_DIR.get_type() is str
    assert AgentConfigOptions.BASE_LOG_DIR.get_default_value() is None
