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

package org.apache.flink.agents.api.yaml.spec;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Collections;
import java.util.List;

/** One agent inside a YAML file's {@code agents:} list. */
@JsonIgnoreProperties(ignoreUnknown = false)
public final class AgentSpec {
    private final String name;
    private final String description;
    private final List<PromptSpec> prompts;
    private final List<ToolSpec> tools;
    private final List<SkillsSpec> skills;
    private final List<AgentActionRef> actions;
    private final List<DescriptorSpec> chatModelConnections;
    private final List<DescriptorSpec> chatModelSetups;
    private final List<DescriptorSpec> embeddingModelConnections;
    private final List<DescriptorSpec> embeddingModelSetups;
    private final List<DescriptorSpec> vectorStores;
    private final List<DescriptorSpec> mcpServers;

    @JsonCreator
    public AgentSpec(
            @JsonProperty(value = "name", required = true) String name,
            @JsonProperty("description") String description,
            @JsonProperty("prompts") List<PromptSpec> prompts,
            @JsonProperty("tools") List<ToolSpec> tools,
            @JsonProperty("skills") List<SkillsSpec> skills,
            @JsonProperty("actions") List<AgentActionRef> actions,
            @JsonProperty("chat_model_connections") List<DescriptorSpec> chatModelConnections,
            @JsonProperty("chat_model_setups") List<DescriptorSpec> chatModelSetups,
            @JsonProperty("embedding_model_connections")
                    List<DescriptorSpec> embeddingModelConnections,
            @JsonProperty("embedding_model_setups") List<DescriptorSpec> embeddingModelSetups,
            @JsonProperty("vector_stores") List<DescriptorSpec> vectorStores,
            @JsonProperty("mcp_servers") List<DescriptorSpec> mcpServers) {
        this.name = name;
        this.description = description;
        this.prompts = orEmpty(prompts);
        this.tools = orEmpty(tools);
        this.skills = orEmpty(skills);
        this.actions = orEmpty(actions);
        this.chatModelConnections = orEmpty(chatModelConnections);
        this.chatModelSetups = orEmpty(chatModelSetups);
        this.embeddingModelConnections = orEmpty(embeddingModelConnections);
        this.embeddingModelSetups = orEmpty(embeddingModelSetups);
        this.vectorStores = orEmpty(vectorStores);
        this.mcpServers = orEmpty(mcpServers);
    }

    private static <T> List<T> orEmpty(List<T> list) {
        return list == null ? Collections.emptyList() : list;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public List<PromptSpec> getPrompts() {
        return prompts;
    }

    public List<ToolSpec> getTools() {
        return tools;
    }

    public List<SkillsSpec> getSkills() {
        return skills;
    }

    public List<AgentActionRef> getActions() {
        return actions;
    }

    public List<DescriptorSpec> getChatModelConnections() {
        return chatModelConnections;
    }

    public List<DescriptorSpec> getChatModelSetups() {
        return chatModelSetups;
    }

    public List<DescriptorSpec> getEmbeddingModelConnections() {
        return embeddingModelConnections;
    }

    public List<DescriptorSpec> getEmbeddingModelSetups() {
        return embeddingModelSetups;
    }

    public List<DescriptorSpec> getVectorStores() {
        return vectorStores;
    }

    public List<DescriptorSpec> getMcpServers() {
        return mcpServers;
    }
}
