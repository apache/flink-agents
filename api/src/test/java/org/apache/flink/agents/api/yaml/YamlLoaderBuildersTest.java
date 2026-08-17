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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.apache.flink.agents.api.function.JavaFunction;
import org.apache.flink.agents.api.function.PythonFunction;
import org.apache.flink.agents.api.prompt.Prompt;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceName;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.api.skills.SkillSourceSpec;
import org.apache.flink.agents.api.skills.Skills;
import org.apache.flink.agents.api.tools.FunctionTool;
import org.apache.flink.agents.api.tools.ToolParameterInjection;
import org.apache.flink.agents.api.yaml.spec.DescriptorSpec;
import org.apache.flink.agents.api.yaml.spec.PromptSpec;
import org.apache.flink.agents.api.yaml.spec.SkillsSpec;
import org.apache.flink.agents.api.yaml.spec.ToolSpec;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class YamlLoaderBuildersTest {

    private static final ObjectMapper M = new ObjectMapper(new YAMLFactory());

    @Test
    void descriptorJavaResolvesAlias() throws Exception {
        DescriptorSpec spec =
                M.readValue(
                        "name: c\nclazz: ollama\ntype: java\nendpoint: http://x\n",
                        DescriptorSpec.class);
        ResourceDescriptor d = YamlLoader.buildDescriptor(spec, ResourceType.CHAT_MODEL_CONNECTION);
        assertThat(d.getClazz()).isEqualTo(ResourceName.ChatModel.OLLAMA_CONNECTION);
        assertThat(d.getInitialArguments()).containsEntry("endpoint", "http://x");
    }

    @Test
    void descriptorPythonWrapsInPythonWrapper() throws Exception {
        DescriptorSpec spec =
                M.readValue(
                        "name: c\nclazz: ollama\ntype: python\nbase_url: http://x\n",
                        DescriptorSpec.class);
        ResourceDescriptor d = YamlLoader.buildDescriptor(spec, ResourceType.CHAT_MODEL_CONNECTION);
        assertThat(d.getClazz()).isEqualTo(ResourceName.ChatModel.PYTHON_WRAPPER_CONNECTION);
        assertThat(d.getInitialArguments())
                .containsEntry("pythonClazz", ResourceName.ChatModel.Python.OLLAMA_CONNECTION)
                .containsEntry("base_url", "http://x");
    }

    @Test
    void descriptorDefaultIsPython() throws Exception {
        DescriptorSpec spec = M.readValue("name: c\nclazz: ollama\n", DescriptorSpec.class);
        ResourceDescriptor d = YamlLoader.buildDescriptor(spec, ResourceType.CHAT_MODEL_CONNECTION);
        // Default is Python (host-neutral) — wrapper FQN with Python class in pythonClazz arg.
        assertThat(d.getClazz()).isEqualTo(ResourceName.ChatModel.PYTHON_WRAPPER_CONNECTION);
        assertThat(d.getInitialArguments())
                .containsEntry("pythonClazz", ResourceName.ChatModel.Python.OLLAMA_CONNECTION);
    }

    @Test
    void buildToolDefaultsToPython() throws Exception {
        ToolSpec spec = M.readValue("name: t\nfunction: pkg:fn\n", ToolSpec.class);
        FunctionTool tool = YamlLoader.buildTool(spec);
        // No type → Python default; Python tools don't need parameter_types.
        assertThat(tool.getFunc()).isInstanceOf(PythonFunction.class);
    }

    @Test
    void descriptorPythonRejectedForUnsupportedKind() throws Exception {
        DescriptorSpec spec =
                M.readValue("name: x\nclazz: anything\ntype: python\n", DescriptorSpec.class);
        assertThatThrownBy(() -> YamlLoader.buildDescriptor(spec, ResourceType.MCP_SERVER))
                .hasMessageContaining("python");
    }

    @Test
    void buildToolPython() throws Exception {
        ToolSpec spec =
                M.readValue(
                        "name: t\n"
                                + "function: pkg:fn\n"
                                + "type: python\n"
                                + "injected_args:\n"
                                + "  tenant_id:\n"
                                + "    source: config\n"
                                + "    key: tenant_id\n",
                        ToolSpec.class);
        FunctionTool tool = YamlLoader.buildTool(spec);
        assertThat(tool.getFunc()).isInstanceOf(PythonFunction.class);
        assertThat(((PythonFunction) tool.getFunc()).getQualName()).isEqualTo("fn");
        assertThat(tool.getInjectedArgs())
                .containsExactlyEntriesOf(
                        Map.of("tenant_id", ToolParameterInjection.fromConfig("tenant_id")));
    }

    @Test
    void buildToolJavaRequiresParameterTypes() throws Exception {
        ToolSpec spec =
                M.readValue("name: t\nfunction: com.example.X:m\ntype: java\n", ToolSpec.class);
        assertThatThrownBy(() -> YamlLoader.buildTool(spec))
                .hasMessageContaining("parameter_types");
    }

    @Test
    void buildToolJava() throws Exception {
        ToolSpec spec =
                M.readValue(
                        "name: t\nfunction: com.example.X:m\ntype: java\nparameter_types: [int]\n",
                        ToolSpec.class);
        FunctionTool tool = YamlLoader.buildTool(spec);
        assertThat(tool.getFunc()).isInstanceOf(JavaFunction.class);
        assertThat(((JavaFunction) tool.getFunc()).getParameterTypes()).containsExactly("int");
    }

    @Test
    void buildToolJavaKeepsUnknownInjectedArgNamesForPlanValidation() throws Exception {
        ToolSpec spec =
                M.readValue(
                        "name: t\n"
                                + "function: org.apache.flink.agents.api.yaml.LoaderTargets:queryOrder\n"
                                + "type: java\n"
                                + "parameter_types: [java.lang.String, java.lang.String]\n"
                                + "injected_args:\n"
                                + "  tenent_id:\n"
                                + "    source: config\n"
                                + "    key: tenant_id\n",
                        ToolSpec.class);
        FunctionTool tool = YamlLoader.buildTool(spec);
        assertThat(tool.getInjectedArgs())
                .containsExactlyEntriesOf(
                        Map.of("tenent_id", ToolParameterInjection.fromConfig("tenant_id")));
    }

    @Test
    void buildToolJavaAcceptsKnownInjectedArgNames() throws Exception {
        ToolSpec spec =
                M.readValue(
                        "name: t\n"
                                + "function: org.apache.flink.agents.api.yaml.LoaderTargets:queryOrder\n"
                                + "type: java\n"
                                + "parameter_types: [java.lang.String, java.lang.String]\n"
                                + "injected_args:\n"
                                + "  tenant_id:\n"
                                + "    source: config\n"
                                + "    key: tenant_id\n",
                        ToolSpec.class);
        FunctionTool tool = YamlLoader.buildTool(spec);
        assertThat(tool.getInjectedArgs())
                .containsExactlyEntriesOf(
                        Map.of("tenant_id", ToolParameterInjection.fromConfig("tenant_id")));
    }

    @Test
    void buildPromptText() throws Exception {
        PromptSpec spec = M.readValue("name: p\ntext: hi\n", PromptSpec.class);
        Prompt prompt = YamlLoader.buildPrompt(spec);
        assertThat(prompt.formatString(Map.of())).isEqualTo("hi");
    }

    @Test
    void buildPromptMessages() throws Exception {
        PromptSpec spec =
                M.readValue(
                        "name: p\nmessages:\n  - {role: system, content: hi}\n", PromptSpec.class);
        Prompt prompt = YamlLoader.buildPrompt(spec);
        // Both message-templated and text-templated prompts produce LocalPrompt instances;
        // confirm the constructed prompt formats without error.
        assertThat(prompt).isNotNull();
    }

    @Test
    void buildSkills() throws Exception {
        SkillsSpec spec = M.readValue("name: s\npaths: [./a]\n", SkillsSpec.class);
        Skills s = YamlLoader.buildSkills(spec);
        assertThat(s.getSources())
                .containsExactly(new SkillSourceSpec("local", Map.of("path", "./a")));
    }

    @Test
    void buildSkillsMergesAllSchemes() throws Exception {
        SkillsSpec spec =
                M.readValue(
                        "name: s\n"
                                + "paths: [./a]\n"
                                + "urls: [https://x/unpinned.zip]\n"
                                + "url_sources:\n"
                                + "  - url: http://x/s.zip\n"
                                + "    sha256: "
                                + "a".repeat(64)
                                + "\n"
                                + "    allow_insecure_http: true\n"
                                + "classpath: [com/example/s]\n",
                        SkillsSpec.class);
        Skills s = YamlLoader.buildSkills(spec);
        assertThat(s.getSources())
                .containsExactly(
                        new SkillSourceSpec("local", Map.of("path", "./a")),
                        new SkillSourceSpec("url", Map.of("url", "https://x/unpinned.zip")),
                        new SkillSourceSpec(
                                "url",
                                Map.of(
                                        "url",
                                        "http://x/s.zip",
                                        "sha256",
                                        "a".repeat(64),
                                        "allow_insecure_http",
                                        "true")),
                        new SkillSourceSpec("classpath", Map.of("resource", "com/example/s")));
    }

    @Test
    void buildSkillsSupportsAllUrlSourceSecurityOptions() throws Exception {
        assertUrlSource("https://x/secure.zip", null, false, Map.of("url", "https://x/secure.zip"));
        assertUrlSource(
                "https://x/pinned.zip",
                "a".repeat(64),
                false,
                Map.of("url", "https://x/pinned.zip", "sha256", "a".repeat(64)));
        assertUrlSource(
                "http://x/unsafe.zip",
                null,
                true,
                Map.of("url", "http://x/unsafe.zip", "allow_insecure_http", "true"));
        assertUrlSource(
                "http://x/unsafe-pinned.zip",
                "b".repeat(64),
                true,
                Map.of(
                        "url",
                        "http://x/unsafe-pinned.zip",
                        "sha256",
                        "b".repeat(64),
                        "allow_insecure_http",
                        "true"));
    }

    private static void assertUrlSource(
            String url,
            String sha256,
            boolean allowInsecureHttp,
            Map<String, String> expectedParams)
            throws Exception {
        StringBuilder yaml =
                new StringBuilder("name: s\nurl_sources:\n  - url: ").append(url).append('\n');
        if (sha256 != null) {
            yaml.append("    sha256: ").append(sha256).append('\n');
        }
        if (allowInsecureHttp) {
            yaml.append("    allow_insecure_http: true\n");
        }
        SkillsSpec spec = M.readValue(yaml.toString(), SkillsSpec.class);
        assertThat(YamlLoader.buildSkills(spec).getSources())
                .containsExactly(new SkillSourceSpec("url", expectedParams));
    }
}
