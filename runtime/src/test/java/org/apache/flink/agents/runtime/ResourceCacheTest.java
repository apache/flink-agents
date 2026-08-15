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

import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.api.EventType;
import org.apache.flink.agents.api.InputEvent;
import org.apache.flink.agents.api.agents.Agent;
import org.apache.flink.agents.api.annotation.ChatModelSetup;
import org.apache.flink.agents.api.annotation.Tool;
import org.apache.flink.agents.api.chat.messages.ChatMessage;
import org.apache.flink.agents.api.chat.model.python.PythonChatModelSetup;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.api.resource.Resource;
import org.apache.flink.agents.api.resource.ResourceContext;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.api.resource.SerializableResource;
import org.apache.flink.agents.api.resource.python.PythonResourceAdapter;
import org.apache.flink.agents.api.resource.python.PythonResourceWrapper;
import org.apache.flink.agents.api.vectorstores.Document;
import org.apache.flink.agents.api.vectorstores.VectorStoreQuery;
import org.apache.flink.agents.api.vectorstores.VectorStoreQueryResult;
import org.apache.flink.agents.plan.AgentPlan;
import org.apache.flink.agents.runtime.resource.ResourceContextImpl;
import org.apache.flink.agents.runtime.skill.SkillManager;
import org.junit.jupiter.api.Test;
import pemja.core.object.PyObject;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

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
                ResourceContext resourceContext) {
            super(descriptor, resourceContext);
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

        @org.apache.flink.agents.api.annotation.Action(EventType.InputEvent)
        public void handleInputEvent(Event event, RunnerContext context) {
            InputEvent inputEvent = InputEvent.fromEvent(event);
        }
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

        @Override
        public Map<String, String> getPythonToolMetadata(String module, String qualName) {
            return Map.of("name", qualName, "description", "", "inputSchema", "{}");
        }

        @Override
        public Map<String, String> getPythonToolMetadata(
                String module, String qualName, List<String> injectedArgs) {
            return getPythonToolMetadata(module, qualName);
        }

        @Override
        public Object invokePythonTool(String module, String qualName, Map<String, Object> kwargs) {
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
    public void testHasResourceCoversProvidersAndCachedOnlyEntries() throws Exception {
        TestAgentWithResources agent = new TestAgentWithResources();
        AgentPlan agentPlan = new AgentPlan(agent);
        ResourceCache cache = new ResourceCache(agentPlan.getResourceProviders());

        // registered provider, resource not yet created
        assertThat(cache.hasResource("myTool", ResourceType.TOOL)).isTrue();
        assertThat(cache.hasResource("non-existent", ResourceType.TOOL)).isFalse();

        // a resource inserted directly into the cache has no provider but must still be visible
        Resource cachedOnly =
                new Resource(new ResourceDescriptor("cachedOnly", Map.of()), null) {
                    @Override
                    public ResourceType getResourceType() {
                        return ResourceType.CHAT_MODEL;
                    }
                };
        assertThat(cache.hasResource("cachedOnly", ResourceType.CHAT_MODEL)).isFalse();
        cache.put("cachedOnly", ResourceType.CHAT_MODEL, cachedOnly);
        assertThat(cache.hasResource("cachedOnly", ResourceType.CHAT_MODEL)).isTrue();
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

    /**
     * A cached resource failing with a non-{@code Exception} {@code Throwable} must not strand the
     * remaining resources, the cache clear, or the resource context. {@code
     * ActionExecutionOperator.close()} closes this cache before the Python interpreter precisely
     * because cached resources may hold Python references, so leaving resources open here while the
     * interpreter behind them is torn down would break that ordering.
     */
    @Test
    public void closeClosesEveryResourceWhenAnEarlierResourceThrowsError() throws Exception {
        ResourceCache cache = new ResourceCache(new HashMap<>());
        OutOfMemoryError failure = new OutOfMemoryError("resource close failed");
        RecordingResource failing = new RecordingResource(ResourceType.TOOL, failure);
        RecordingResource surviving = new RecordingResource(ResourceType.CHAT_MODEL, null);
        cache.put("failing", ResourceType.TOOL, failing);
        cache.put("surviving", ResourceType.CHAT_MODEL, surviving);
        // Stands in for the lazily-cached skill manager, so that resourceContext.close() running
        // is observable rather than merely assumed from its position in the method.
        SkillManager skillManager = mock(SkillManager.class);
        setSkillManager(cache.getResourceContext(), skillManager);

        // The Error reaches the caller unchanged rather than wrapped in an Exception, and with
        // nothing attached to it.
        assertThatThrownBy(cache::close)
                .isSameAs(failure)
                .satisfies(thrown -> assertThat(thrown.getSuppressed()).isEmpty());

        assertThat(failing.closed).isTrue();
        assertThat(surviving.closed).isTrue();
        // Neither the cache clear nor the resource context is skipped by the Error.
        assertThat(cachedResources(cache)).isEmpty();
        verify(skillManager).close();
    }

    /** The first failure is rethrown and any later one is attached as suppressed, never dropped. */
    @Test
    public void closeReportsFirstResourceFailureWithLaterOnesSuppressed() throws Exception {
        ResourceCache cache = new ResourceCache(new HashMap<>());
        RecordingResource first =
                new RecordingResource(ResourceType.TOOL, new IllegalStateException("first"));
        RecordingResource second =
                new RecordingResource(ResourceType.TOOL, new IllegalStateException("second"));
        cache.put("first", ResourceType.TOOL, first);
        cache.put("second", ResourceType.TOOL, second);

        Throwable thrown = catchThrowable(cache::close);

        // Iteration order over the cache is unspecified, so pin the aggregation rather than which
        // of the two lands first: one is thrown and the other is suppressed on it.
        assertThat(thrown).isInstanceOf(IllegalStateException.class);
        assertThat(thrown.getSuppressed()).hasSize(1);
        assertThat(new String[] {thrown.getMessage(), thrown.getSuppressed()[0].getMessage()})
                .containsExactlyInAnyOrder("first", "second");
        assertThat(first.closed).isTrue();
        assertThat(second.closed).isTrue();
    }

    private static void setSkillManager(ResourceContextImpl context, SkillManager skillManager)
            throws Exception {
        Field field = ResourceContextImpl.class.getDeclaredField("skillManager");
        field.setAccessible(true);
        field.set(context, skillManager);
    }

    @SuppressWarnings("unchecked")
    private static Map<ResourceType, Map<String, Resource>> cachedResources(ResourceCache cache)
            throws Exception {
        Field field = ResourceCache.class.getDeclaredField("cache");
        field.setAccessible(true);
        return (Map<ResourceType, Map<String, Resource>>) field.get(cache);
    }

    /** Records whether {@code close()} ran, and optionally fails it. */
    private static final class RecordingResource extends Resource {
        private final ResourceType type;
        private final Throwable failure;
        private boolean closed = false;

        private RecordingResource(ResourceType type, Throwable failure) {
            this.type = type;
            this.failure = failure;
        }

        @Override
        public ResourceType getResourceType() {
            return type;
        }

        @Override
        public void close() throws Exception {
            closed = true;
            if (failure instanceof Error) {
                throw (Error) failure;
            }
            if (failure instanceof Exception) {
                throw (Exception) failure;
            }
        }
    }
}
