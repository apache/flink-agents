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

package org.apache.flink.agents.runtime.subagent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.agents.api.agents.Agent;
import org.apache.flink.agents.api.chat.model.BaseChatModelSetup;
import org.apache.flink.agents.api.resource.ResourceContext;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.api.subagent.InternalSubagentResourceDescriptorBuilder;
import org.apache.flink.agents.api.subagent.SubagentSetup;
import org.apache.flink.agents.plan.AgentPlan;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class InternalSubagentDescriptorTest {
    private static final String SCHEMA =
            "{\"type\":\"object\",\"properties\":{\"question\":{\"type\":\"string\"}}}";

    private static final class ChatSetup extends BaseChatModelSetup {
        ChatSetup(ResourceContext context) {
            super(new ResourceDescriptor("chat", Map.of("subagents", List.of("child"))), context);
        }

        @Override
        public Map<String, Object> getParameters() {
            return Map.of();
        }
    }

    @Test
    void restoredInternalDescriptorDeclaresAModelCallable() throws Exception {
        Agent root = new Agent();
        root.addResource(
                "child",
                ResourceType.AGENT,
                new InternalSubagentResourceDescriptorBuilder()
                        .agent(new Agent())
                        .description("Answers questions")
                        .inputSchema(SCHEMA)
                        .build());
        ObjectMapper mapper = new ObjectMapper();
        AgentPlan restored =
                mapper.readValue(mapper.writeValueAsString(new AgentPlan(root)), AgentPlan.class);
        ResourceContext context = mock(ResourceContext.class);
        SubagentSetup child =
                (SubagentSetup)
                        restored.getResourceProviders()
                                .get(ResourceType.AGENT)
                                .get("child")
                                .provide(context);
        assertThat(child).isInstanceOf(InternalSubagentSetup.class);
        assertThat(child.getResourceContext()).isSameAs(context);
        assertThat(child.getDescription()).isEqualTo("Answers questions");
        assertThat(child.getInputSchema()).isEqualTo(SCHEMA);
        when(context.getResource("child", ResourceType.AGENT)).thenReturn(child);
        ChatSetup chat = new ChatSetup(context);
        chat.open();
        assertThat(chat.getTools()).hasSize(1);
        assertThat(chat.getTools().get(0).getName()).isEqualTo("_subagent_child");
    }
}
