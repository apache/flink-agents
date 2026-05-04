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

package org.apache.flink.agents.runtime;

import org.apache.flink.agents.api.InputEvent;
import org.apache.flink.agents.api.agents.Agent;
import org.apache.flink.agents.api.annotation.ChatModelSetup;
import org.apache.flink.agents.api.annotation.Tool;
import org.apache.flink.agents.api.chat.messages.ChatMessage;
import org.apache.flink.agents.api.chat.model.python.PythonChatModelSetup;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.api.resource.Resource;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.api.resource.SerializableResource;
import org.apache.flink.agents.api.resource.python.PythonResourceAdapter;
import org.apache.flink.agents.api.resource.python.PythonResourceWrapper;
import org.apache.flink.agents.api.vectorstores.Document;
import org.apache.flink.agents.api.vectorstores.VectorStoreQuery;
import org.apache.flink.agents.api.vectorstores.VectorStoreQueryResult;
import org.apache.flink.agents.plan.AgentPlan;
import org.junit.jupiter.api.Test;
import pemja.core.object.PyObject;

import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Tests for {@link ResourceCache}. */
public class ResourceCacheTest {

    /** Test tool resource class. */
    public static class TestTool extends SerializableResource {
        private final String name;

        public TestTool(String name) {
            this.name = name;
        }

        public String getName() {
            return name;
        }

        @Override
        public ResourceType getResourceType() {
            return ResourceType.TOOL;
        }
    }

    /** Test serializable chat model resource class. */
    public static class TestSerializableChatModel extends SerializableResource {
        private final String name;

        public TestSerializableChatModel(String name) {
            this.name = name;
        }

        public String getName() {
            return name;
        }

        @Override
        public ResourceType getResourceType() {
            return ResourceType.CHAT_MODEL;
        }
    }

    public static class TestPythonResource extends Resource implements PythonResourceWrapper {

        public TestPythonResource(
                PythonResourceAdapter adapter,
                PyObject chatModel,
                ResourceDescriptor descriptor,
                BiFunction<String, ResourceType, Resource> getResource) {
            super(descriptor, getResource);
        }

        @Override
        public ResourceType getResourceType() {
            return ResourceType.CHAT_MODEL;
        }

        @Override
        public Object getPythonResource() {
            return null;
        }
    }

    /** Test agent class with resource annotations. */
    public static class TestAgentWithResources extends Agent {

        @Tool private TestTool myTool = new TestTool("myTool");

        @ChatModelSetup
        private TestSerializableChatModel chatModel =
                new TestSerializableChatModel("defaultChatModel");

        @ChatModelSetup
        public static ResourceDescriptor pythonChatModel() {
            return ResourceDescriptor.Builder.newBuilder(TestPythonResource.class.getName())
                    .addInitialArgument("pythonClazz", "test.module.TestClazz")
                    .build();
        }

        @Tool private TestTool anotherTool = new TestTool("anotherTool");

        @org.apache.flink.agents.api.annotation.Action(listenEvents = {InputEvent.class})
        public void handleInputEvent(InputEvent event, RunnerContext context) {}
    }

    public static class TestPythonResourceAdapter implements PythonResourceAdapter {

        @Override
        public Object getResource(String resourceName, String resourceType) {
            return null;
        }

        @Override
        public PyObject initPythonResource(
                String module, String clazz, Map<String, Object> kwargs) {
            return null;
        }

        @Override
        public Object toPythonChatMessage(ChatMessage message) {
            return null;
        }

        @Override
        public ChatMessage fromPythonChatMessage(Object pythonChatMessage) {
            return null;
        }

        @Override
        public Object toPythonDocuments(List<Document> documents) {
            return null;
        }

        @Override
        public List<Document> fromPythonDocuments(List<PyObject> pythonDocuments) {
            return List.of();
        }

        @Override
        public Object toPythonVectorStoreQuery(VectorStoreQuery query) {
            return null;
        }

        @Override
        public VectorStoreQueryResult fromPythonVectorStoreQueryResult(
                PyObject pythonVectorStoreQueryResult) {
            return null;
        }

        @Override
        public Object convertToPythonTool(org.apache.flink.agents.api.tools.Tool tool) {
            return null;
        }

        @Override
        public Object callMethod(Object obj, String methodName, Map<String, Object> kwargs) {
            return null;
        }

        @Override
        public Object invoke(String name, Object... args) {
            return null;
        }
    }

    @Test
    public void testGetResourceNotFound() throws Exception {
        Agent agent = new Agent();
        AgentPlan agentPlan = new AgentPlan(agent);
        ResourceCache cache = new ResourceCache(agentPlan.getResourceProviders());

        assertThatThrownBy(() -> cache.getResource("non-existent", ResourceType.CHAT_MODEL))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Resource not found: non-existent");
    }

    @Test
    public void testGetResourceFromResourceProvider() throws Exception {
        TestAgentWithResources agent = new TestAgentWithResources();
        AgentPlan agentPlan = new AgentPlan(agent);
        ResourceCache cache = new ResourceCache(agentPlan.getResourceProviders());

        // Test getting a tool resource
        Resource myTool = cache.getResource("myTool", ResourceType.TOOL);
        assertThat(myTool).isNotNull();
        assertThat(myTool).isInstanceOf(TestTool.class);
        assertThat(myTool.getResourceType()).isEqualTo(ResourceType.TOOL);

        // Test getting a chat model resource
        Resource chatModel = cache.getResource("chatModel", ResourceType.CHAT_MODEL);
        assertThat(chatModel).isNotNull();
        assertThat(chatModel).isInstanceOf(TestSerializableChatModel.class);
        assertThat(chatModel.getResourceType()).isEqualTo(ResourceType.CHAT_MODEL);

        assertThatThrownBy(() -> cache.getResource("pythonChatModel", ResourceType.CHAT_MODEL))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PythonResourceAdapter is not set");

        PythonMCPResourceDiscovery.discoverPythonMCPResources(
                agentPlan.getResourceProviders(), new TestPythonResourceAdapter(), cache);
        Resource pythonChatModel = cache.getResource("pythonChatModel", ResourceType.CHAT_MODEL);
        assertThat(pythonChatModel).isNotNull();
        assertThat(pythonChatModel).isInstanceOf(PythonChatModelSetup.class);
        assertThat(pythonChatModel.getResourceType()).isEqualTo(ResourceType.CHAT_MODEL);

        // Test that resources are cached (should be the same instance)
        Resource myToolAgain = cache.getResource("myTool", ResourceType.TOOL);
        assertThat(myTool).isSameAs(myToolAgain);
    }
}
