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
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.api.subagent.InternalSubagentResourceDescriptorBuilder;
import org.apache.flink.agents.api.yaml.spec.YamlAgentsDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class YamlSubagentTest {
    @TempDir Path directory;

    private YamlLoader.LoadedFile load(String yaml) throws Exception {
        Path path = directory.resolve("agents.yaml");
        Files.writeString(path, yaml);
        return YamlLoader.buildAgents(path);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void resolvesChildInEitherOrder(boolean childFirst) throws Exception {
        String parent =
                "  - name: parent\n    subagents:\n"
                        + "      - name: reviewer\n        agent: child\n"
                        + "        description: Review code\n"
                        + "        input_schema: {type: object, properties: {code: {type: string}}}\n";
        String child =
                "  - name: child\n    prompts:\n      - {name: child_prompt, text: 'Review {code}'}\n";
        YamlLoader.LoadedFile result =
                load("agents:\n" + (childFirst ? child + parent : parent + child));
        ResourceDescriptor descriptor =
                (ResourceDescriptor)
                        result.getAgents()
                                .get("parent")
                                .getResources()
                                .get(ResourceType.AGENT)
                                .get("reviewer");
        assertThat(descriptor.getClazz())
                .isEqualTo(InternalSubagentResourceDescriptorBuilder.SETUP_CLASS);
        assertThat(descriptor.getInitialArguments().get("agent"))
                .isSameAs(result.getAgents().get("child"));
        assertThat(descriptor.getInitialArguments()).containsEntry("description", "Review code");
        assertThat(
                        new ObjectMapper()
                                .readTree(
                                        (String)
                                                descriptor
                                                        .getInitialArguments()
                                                        .get("input_schema"))
                                .at("/properties/code/type")
                                .asText())
                .isEqualTo("string");
        assertThat(result.getAgents().get("child").getResources().get(ResourceType.PROMPT))
                .containsKey("child_prompt");
    }

    @Test
    void externalDeclarationsPreserveProviderArguments() throws Exception {
        String external =
                "{name: remote, clazz: example.RemoteSetup, type: java, agent: remote-id}";
        YamlLoader.LoadedFile result =
                load(
                        "agents: [{name: parent, subagents: ["
                                + external
                                + "]}]\nsubagents: ["
                                + external
                                + "]");
        ResourceDescriptor shared =
                (ResourceDescriptor)
                        result.getSharedResources().get(ResourceType.AGENT).get("remote");
        ResourceDescriptor local =
                (ResourceDescriptor)
                        result.getAgents()
                                .get("parent")
                                .getResources()
                                .get(ResourceType.AGENT)
                                .get("remote");
        assertThat(shared.getInitialArguments()).containsEntry("agent", "remote-id");
        assertThat(local.getInitialArguments()).containsEntry("agent", "remote-id");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", ", description: null", ", description: ''"})
    void optionalMetadata(String metadata) throws Exception {
        YamlLoader.LoadedFile result =
                load(
                        "agents: [{name: parent, subagents: [{name: reviewer, agent: child"
                                + metadata
                                + "}]}, {name: child}]");
        ResourceDescriptor descriptor =
                (ResourceDescriptor)
                        result.getAgents()
                                .get("parent")
                                .getResources()
                                .get(ResourceType.AGENT)
                                .get("reviewer");
        assertThat(descriptor.getInitialArguments())
                .containsEntry("description", "")
                .doesNotContainKey("input_schema");
    }

    @Test
    void sharedInternalReferenceIsRejected() {
        assertThatThrownBy(
                        () ->
                                load(
                                        "agents: [{name: child}]\nsubagents: [{name: reviewer, agent: child}]"))
                .hasMessageContaining("Failed to read YAML")
                .hasStackTraceContaining("clazz");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {"agents: [{name: parent, subagents: [{name: reviewer, agent: missing}]}]"})
    void unknownReferenceNamesTheMissingChild(String yaml) {
        assertThatThrownBy(() -> load(yaml))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reviewer")
                .hasMessageContaining("missing")
                .hasMessageContaining("same YAML file");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{name: reviewer, agent: ''}",
                "{name: reviewer, agent: '   '}",
                "{name: reviewer, agent: null}",
                "{name: reviewer, agent: 123}",
                "{name: reviewer, agent: true}",
                "{name: null, agent: child}",
                "{name: 123, agent: child}",
                "{name: reviewer, agent: child, description: 123}",
                "{name: reviewer, agent: child, type: java}",
                "{name: reviewer, agent: child, input_schema: '{}'}",
                "{name: reviewer, agent: child, unexpected: true}",
                "{name: reviewer}"
            })
    void rejectsInvalidDeclarations(String entry) {
        assertThatThrownBy(
                        () ->
                                new ObjectMapper(new YAMLFactory())
                                        .readValue(
                                                "agents: [{name: parent, subagents: ["
                                                        + entry
                                                        + "]}]",
                                                YamlAgentsDocument.class))
                .isInstanceOf(Exception.class);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "agents: [{name: child}]\nsubagents: [{name: reviewer, clazz: example.Remote, type: java}, {name: reviewer, clazz: example.Remote, type: java}]",
                "agents: [{name: child}, {name: parent, subagents: [{name: reviewer, agent: child}, {name: reviewer, agent: child}]}]"
            })
    void duplicateResourceNamesAreRejected(String yaml) {
        assertThatThrownBy(() -> load(yaml)).isInstanceOf(IllegalArgumentException.class);
    }
}
