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

package org.apache.flink.agents.api.subagent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.agents.api.agents.Agent;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class InternalSubagentResourceDescriptorBuilderTest {
    public static class Request {
        public String question;
    }

    @Test
    void buildsAnOrdinaryDescriptorWithSchemaData() throws Exception {
        Agent agent = new Agent();
        ResourceDescriptor descriptor =
                new InternalSubagentResourceDescriptorBuilder()
                        .agent(agent)
                        .description("Answers questions")
                        .inputType(Request.class)
                        .build();
        assertThat(descriptor).isExactlyInstanceOf(ResourceDescriptor.class);
        assertThat(descriptor.getInitialArguments())
                .containsEntry("agent", agent)
                .containsEntry("description", "Answers questions");
        String schema = descriptor.getArgument("input_schema");
        assertThat(new ObjectMapper().readTree(schema).path("properties").has("question")).isTrue();
        assertThat(descriptor.getInitialArguments()).doesNotContainKey("input_type");
    }

    @Test
    void requiresAgentAndUnambiguousInputDeclaration() {
        assertThatThrownBy(() -> new InternalSubagentResourceDescriptorBuilder().build())
                .hasMessageContaining("requires an agent");
        assertThatThrownBy(
                        () ->
                                new InternalSubagentResourceDescriptorBuilder()
                                        .agent(new Agent())
                                        .inputType(Request.class)
                                        .inputSchema("{}")
                                        .build())
                .hasMessageContaining("not both");
        assertThatThrownBy(
                        () ->
                                new InternalSubagentResourceDescriptorBuilder()
                                        .agent(new Agent())
                                        .inputSchema(" ")
                                        .build())
                .hasMessageContaining("must not be blank");
        assertThatThrownBy(
                        () ->
                                new InternalSubagentResourceDescriptorBuilder()
                                        .agent(new Agent())
                                        .inputType(Object.class)
                                        .build())
                .hasMessageContaining("must describe an object");
    }
}
