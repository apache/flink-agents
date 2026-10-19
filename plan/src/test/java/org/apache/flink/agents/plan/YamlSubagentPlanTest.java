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

package org.apache.flink.agents.plan;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.api.yaml.YamlLoader;
import org.apache.flink.agents.plan.resourceprovider.JavaResourceProvider;
import org.apache.flink.agents.plan.subagent.InternalSubagentPlans;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class YamlSubagentPlanTest {
    @TempDir Path directory;

    private AgentPlan compile(String yaml) throws Exception {
        Path path = directory.resolve("agents.yaml");
        Files.writeString(path, yaml);
        return new AgentPlan(YamlLoader.buildAgents(path).getAgents().get("parent"));
    }

    @Test
    void nestedAndReusedChildrenSurvivePlanSerialization() throws Exception {
        AgentPlan original =
                compile(
                        "agents:\n"
                                + "  - name: parent\n    prompts: [{name: parent_only, text: parent}]\n"
                                + "    subagents:\n"
                                + "      - {name: first, agent: child, description: Review, input_schema: {type: object}}\n"
                                + "      - {name: second, agent: child}\n"
                                + "  - name: child\n    prompts: [{name: child_only, text: child}]\n"
                                + "    subagents: [{name: leaf, agent: leaf}]\n"
                                + "  - name: leaf\n");
        ObjectMapper mapper = new ObjectMapper();
        AgentPlan plan = mapper.readValue(mapper.writeValueAsString(original), AgentPlan.class);
        JavaResourceProvider first =
                (JavaResourceProvider)
                        plan.getResourceProviders().get(ResourceType.AGENT).get("first");
        JavaResourceProvider second =
                (JavaResourceProvider)
                        plan.getResourceProviders().get(ResourceType.AGENT).get("second");
        assertThat(first.getDescriptor().getInitialArguments())
                .containsEntry("scope", "first")
                .containsEntry("description", "Review");
        assertThat(first.getDescriptor().getInitialArguments().get("plan"))
                .isEqualTo(second.getDescriptor().getInitialArguments().get("plan"));
        assertThat(
                        mapper.readTree(
                                        (String)
                                                first.getDescriptor()
                                                        .getInitialArguments()
                                                        .get("input_schema"))
                                .get("type")
                                .asText())
                .isEqualTo("object");
        AgentPlan child = InternalSubagentPlans.decode(first.getDescriptor());
        assertThat(child.getResourceProviders().get(ResourceType.AGENT)).containsKey("leaf");
        assertThat(child.getResourceProviders().get(ResourceType.PROMPT))
                .containsKey("child_only")
                .doesNotContainKey("parent_only");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "agents: [{name: parent, subagents: [{name: self, agent: parent}]}]",
                "agents: [{name: parent, subagents: [{name: child, agent: child}]}, {name: child, subagents: [{name: parent, agent: parent}]}]",
                "agents: [{name: parent, subagents: [{name: a, agent: a}]}, {name: a, subagents: [{name: b, agent: b}]}, {name: b, subagents: [{name: a, agent: a}]}]"
            })
    void recursiveReferencesFailPlanConstruction(String yaml) {
        assertThatThrownBy(() -> compile(yaml)).hasMessageContaining("Cyclic sub-agent");
    }
}
