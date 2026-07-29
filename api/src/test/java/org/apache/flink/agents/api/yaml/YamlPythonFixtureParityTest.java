/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.agents.api.yaml;

import org.apache.flink.agents.api.agents.Agent;
import org.apache.flink.agents.api.function.PythonFunction;
import org.apache.flink.agents.api.resource.ResourceName;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.api.tools.FunctionTool;
import org.apache.flink.agents.api.yaml.YamlLoader.LoadedFile;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Loads a Python-authored YAML fixture verbatim through the Java loader and confirms it builds a
 * working Agent tree. With the host-neutral Python default for {@code type:}, the same file behaves
 * identically on either side: omitted {@code type:} fields are interpreted as Python on both
 * loaders.
 */
class YamlPythonFixtureParityTest {

    private static final Path FIXTURE =
            Path.of("src/test/resources/yaml/python-parity/yaml_test_agent.yaml");

    @Test
    void buildsPythonAuthoredYamlVerbatim() {
        LoadedFile out = YamlLoader.buildAgents(FIXTURE);

        Agent agent = out.getAgents().get("yaml_test_agent");
        assertThat(agent).isNotNull();

        // Action `function:` with no `type:` lands as a PythonFunction (no parameter_types needed).
        PythonFunction processInput = (PythonFunction) agent.getActions().get("process_input").f1;
        assertThat(processInput.getQualName()).isEqualTo("process_input");

        // Tool `function:` with no `type:` lands as a PythonFunction-backed FunctionTool.
        FunctionTool addTool =
                (FunctionTool) agent.getResources().get(ResourceType.TOOL).get("add");
        assertThat(addTool.getFunc()).isInstanceOf(PythonFunction.class);

        // Descriptor `clazz: ollama` with no `type:` is wrapped in the Java-side Python
        // wrapper class, with the resolved Python FQN passed as the `pythonClazz` init arg.
        Object connection =
                agent.getResources()
                        .get(ResourceType.CHAT_MODEL_CONNECTION)
                        .get("ollama_connection");
        assertThat(connection)
                .isInstanceOf(org.apache.flink.agents.api.resource.ResourceDescriptor.class);
        org.apache.flink.agents.api.resource.ResourceDescriptor descriptor =
                (org.apache.flink.agents.api.resource.ResourceDescriptor) connection;
        assertThat(descriptor.getClazz())
                .isEqualTo(ResourceName.ChatModel.PYTHON_WRAPPER_CONNECTION);
        assertThat(descriptor.getInitialArguments())
                .containsEntry("pythonClazz", ResourceName.ChatModel.Python.OLLAMA_CONNECTION);
    }
}
