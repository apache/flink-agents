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

import org.apache.flink.agents.api.Agent;
import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.api.InputEvent;
import org.apache.flink.agents.api.OutputEvent;
import org.apache.flink.agents.api.annotation.ChatModelSetup;
import org.apache.flink.agents.api.annotation.Tool;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.api.resource.Resource;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.api.resource.SerializableResource;
import org.apache.flink.agents.api.resource.python.PythonResourceAdapter;
import org.apache.flink.agents.api.resource.python.PythonResourceWrapper;
import org.apache.flink.agents.plan.actions.Action;
import org.apache.flink.agents.plan.resourceprovider.JavaSerializableResourceProvider;
import org.apache.flink.agents.plan.resourceprovider.PythonResourceProvider;
import org.apache.flink.agents.plan.resourceprovider.ResourceProvider;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import pemja.core.object.PyObject;

import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Test for {@link AgentPlan} constructor that takes an Agent. */
public class AgentPlanTest {

    /** Test event class for testing. */
    public static class TestEvent extends Event {
        private final String data;

        public TestEvent(String data) {
            this.data = data;
        }

        public String getData() {
            return data;
        }
    }

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

    /** Test agent class with annotated methods. */
    public static class TestAgent extends Agent {

        @org.apache.flink.agents.api.annotation.Action(listenEvents = {InputEvent.class})
        public void handleInputEvent(InputEvent event, RunnerContext context) {
            // Test action implementation
        }

        @org.apache.flink.agents.api.annotation.Action(
                listenEvents = {TestEvent.class, OutputEvent.class})
        public void handleMultipleEvents(Event event, RunnerContext context) {
            // Test action implementation
        }

        // This method is not annotated, so it should not be included
        public void nonAnnotatedMethod(Event event, RunnerContext context) {
            // This should not be included in the agent plan
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
                    .addInitialArgument("module", "test.module")
                    .addInitialArgument("clazz", "TestClazz")
                    .build();
        }

        @Tool private TestTool anotherTool = new TestTool("anotherTool");

        @org.apache.flink.agents.api.annotation.Action(listenEvents = {InputEvent.class})
        public void handleInputEvent(InputEvent event, RunnerContext context) {
            // Test action implementation
        }
    }

    /** Test agent class with illegal python resource. */
    public static class TestAgentWithIllegalPythonResource extends Agent {
        @ChatModelSetup
        public static ResourceDescriptor reviewAnalysisModel() {
            return ResourceDescriptor.Builder.newBuilder(TestPythonResource.class.getName())
                    .build();
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
        public Object callMethod(Object obj, String methodName, Map<String, Object> kwargs) {
            return null;
        }

        @Override
        public Object invoke(String name, Object... args) {
            return null;
        }
    }

    @Test
    public void testConstructorWithAgent() throws Exception {
        // Create an agent instance
        TestAgent agent = new TestAgent();

        // Create AgentPlan using the new constructor
        AgentPlan agentPlan = new AgentPlan(agent);

        // Verify that actions were collected correctly
        assertThat(agentPlan.getActions().size()).isEqualTo(4);
        assertThat(agentPlan.getActions()).containsKey("handleInputEvent");
        assertThat(agentPlan.getActions()).containsKey("handleMultipleEvents");

        // Verify action details for handleInputEvent
        Action inputAction = agentPlan.getActions().get("handleInputEvent");
        assertThat(inputAction).isNotNull();
        assertThat(inputAction.getName()).isEqualTo("handleInputEvent");
        assertThat(inputAction.getListenEventTypes())
                .isEqualTo(List.of(InputEvent.class.getName()));
        assertThat(inputAction.getExec()).isInstanceOf(JavaFunction.class);

        // Check that the JavaFunction instance has the correct method/class/params
        JavaFunction exec = (JavaFunction) inputAction.getExec();
        assertThat(exec.getQualName()).isEqualTo(TestAgent.class.getName());
        assertThat(exec.getMethodName()).isEqualTo("handleInputEvent");
        assertThat(exec.getParameterTypes()).containsExactly(InputEvent.class, RunnerContext.class);

        // Verify action details for handleMultipleEvents
        Action multiAction = agentPlan.getActions().get("handleMultipleEvents");
        assertThat(multiAction).isNotNull();
        assertThat(multiAction.getName()).isEqualTo("handleMultipleEvents");
        assertThat(multiAction.getListenEventTypes())
                .isEqualTo(List.of(TestEvent.class.getName(), OutputEvent.class.getName()));
        assertThat(multiAction.getExec()).isInstanceOf(JavaFunction.class);

        // Verify actionsByEvent mapping
        assertThat(agentPlan.getActionsByEvent().size()).isEqualTo(6);

        // Check InputEvent mapping
        List<Action> inputEventActions =
                agentPlan.getActionsByEvent().get(InputEvent.class.getName());
        assertThat(inputEventActions).isNotNull();
        assertThat(inputEventActions.size()).isEqualTo(1);
        assertThat(inputEventActions.get(0).getName()).isEqualTo("handleInputEvent");

        // Check TestEvent mapping
        List<Action> testEventActions =
                agentPlan.getActionsByEvent().get(TestEvent.class.getName());
        assertThat(testEventActions).isNotNull();
        assertThat(testEventActions.size()).isEqualTo(1);
        assertThat(testEventActions.get(0).getName()).isEqualTo("handleMultipleEvents");

        // Check OutputEvent mapping
        List<Action> outputEventActions =
                agentPlan.getActionsByEvent().get(OutputEvent.class.getName());
        assertThat(outputEventActions).isNotNull();
        assertThat(outputEventActions.size()).isEqualTo(1);
        assertThat(outputEventActions.get(0).getName()).isEqualTo("handleMultipleEvents");
    }

    @Test
    public void testConstructorWithAgentNoActions() throws Exception {
        // Create an agent with no annotated methods
        Agent emptyAgent = new Agent() {
                    // No annotated methods
                };

        // Create AgentPlan using the new constructor
        AgentPlan agentPlan = new AgentPlan(emptyAgent);

        // Verify that no actions were collected
        assertThat(agentPlan.getActions().size()).isEqualTo(2);
        assertThat(agentPlan.getActionsByEvent().size()).isEqualTo(3);
    }

    @Test
    public void testAgentPlanResourceProviders() throws Exception {
        // Test that AgentPlan initializes resource providers correctly
        TestAgent agent = new TestAgent();
        AgentPlan agentPlan = new AgentPlan(agent);

        // Verify that resource providers map is initialized
        Map<ResourceType, Map<String, ResourceProvider>> resourceProviders =
                agentPlan.getResourceProviders();
        assertThat(resourceProviders).isNotNull();

        // The map should be empty for agents without resource annotations
        assertThat(resourceProviders).isEmpty();
    }

    @Test
    public void testAgentAddAction() throws Exception {
        // Construct agent plan from declare api.
        AgentPlan expectedPlan = new AgentPlan(new TestAgent());

        Agent agent = new Agent();
        Map<String, Object> config = Map.of("key", 123);
        agent.addAction(
                        new Class[] {InputEvent.class},
                        TestAgent.class.getMethod(
                                "handleInputEvent", InputEvent.class, RunnerContext.class))
                .addAction(
                        new Class[] {TestEvent.class, OutputEvent.class},
                        TestAgent.class.getMethod(
                                "handleMultipleEvents", Event.class, RunnerContext.class),
                        config);
        AgentPlan actualPlan = new AgentPlan(agent);

        Assertions.assertEquals(expectedPlan.getActions().size(), actualPlan.getActions().size());

        Action expectedInputAction = expectedPlan.getActions().get("handleInputEvent");
        Action actualInputAction = actualPlan.getActions().get("handleInputEvent");
        Assertions.assertEquals(expectedInputAction.getName(), actualInputAction.getName());
        Assertions.assertEquals(expectedInputAction.getExec(), actualInputAction.getExec());
        Assertions.assertEquals(
                expectedInputAction.getListenEventTypes(), actualInputAction.getListenEventTypes());

        expectedInputAction = expectedPlan.getActions().get("handleMultipleEvents");
        actualInputAction = actualPlan.getActions().get("handleMultipleEvents");
        Assertions.assertEquals(expectedInputAction.getName(), actualInputAction.getName());
        Assertions.assertEquals(expectedInputAction.getExec(), actualInputAction.getExec());
        Assertions.assertEquals(
                expectedInputAction.getListenEventTypes(), actualInputAction.getListenEventTypes());
        Assertions.assertEquals(
                123, actualPlan.getActionConfigValue("handleMultipleEvents", "key"));
    }

    @Test
    public void testGetResourceNotFound() throws Exception {
        TestAgent agent = new TestAgent();
        AgentPlan agentPlan = new AgentPlan(agent);

        // Test getting non-existent resource throws exception
        try {
            agentPlan.getResource("non-existent", ResourceType.CHAT_MODEL);
            assertThat(false).as("Should have thrown IllegalArgumentException").isTrue();
        } catch (IllegalArgumentException e) {
            assertThat(e.getMessage()).contains("Resource not found: non-existent");
        }
    }

    @Test
    public void testExtractResourceProvidersFromAgent() throws Exception {
        // Create an agent with resource annotations
        TestAgentWithResources agent = new TestAgentWithResources();
        AgentPlan agentPlan = new AgentPlan(agent);

        // Verify that resource providers were extracted correctly
        Map<ResourceType, Map<String, ResourceProvider>> resourceProviders =
                agentPlan.getResourceProviders();
        assertThat(resourceProviders).isNotNull();
        assertThat(resourceProviders).hasSize(2); // TOOL and CHAT_MODEL

        // Verify TOOL resource providers
        Map<String, ResourceProvider> toolProviders = resourceProviders.get(ResourceType.TOOL);
        assertThat(toolProviders).isNotNull();
        assertThat(toolProviders).hasSize(2); // myTool and anotherTool
        assertThat(toolProviders).containsKey("myTool");
        assertThat(toolProviders).containsKey("anotherTool");

        // Verify that tool providers are JavaSerializableResourceProvider (non-serializable)
        ResourceProvider myToolProvider = toolProviders.get("myTool");
        assertThat(myToolProvider).isInstanceOf(JavaSerializableResourceProvider.class);
        assertThat(myToolProvider.getName()).isEqualTo("myTool");
        assertThat(myToolProvider.getType()).isEqualTo(ResourceType.TOOL);

        ResourceProvider anotherToolProvider = toolProviders.get("anotherTool");
        assertThat(anotherToolProvider).isInstanceOf(JavaSerializableResourceProvider.class);
        assertThat(anotherToolProvider.getName()).isEqualTo("anotherTool");
        assertThat(anotherToolProvider.getType()).isEqualTo(ResourceType.TOOL);

        // Verify CHAT_MODEL resource providers
        Map<String, ResourceProvider> chatModelProviders =
                resourceProviders.get(ResourceType.CHAT_MODEL);
        assertThat(chatModelProviders).isNotNull();
        assertThat(chatModelProviders).hasSize(2); // defaultChatModel (field name used as default)
        assertThat(chatModelProviders).containsKey("chatModel");
        assertThat(chatModelProviders).containsKey("pythonChatModel");

        // Verify that chat model provider is JavaSerializableResourceProvider
        // (serializable)
        ResourceProvider chatModelProvider = chatModelProviders.get("chatModel");
        assertThat(chatModelProvider).isInstanceOf(JavaSerializableResourceProvider.class);
        assertThat(chatModelProvider.getName()).isEqualTo("chatModel");
        assertThat(chatModelProvider.getType()).isEqualTo(ResourceType.CHAT_MODEL);

        // Test JavaSerializableResourceProvider specific methods
        JavaSerializableResourceProvider serializableProvider =
                (JavaSerializableResourceProvider) chatModelProvider;
        assertThat(serializableProvider.getModule())
                .isEqualTo(TestAgentWithResources.class.getPackage().getName());
        assertThat(serializableProvider.getClazz()).contains("TestSerializableChatModel");

        // Verify that python chat model provider is PythonResourceProvider
        // (serializable)
        ResourceProvider pythonChatModelProvider = chatModelProviders.get("pythonChatModel");
        assertThat(pythonChatModelProvider).isInstanceOf(PythonResourceProvider.class);
        assertThat(pythonChatModelProvider.getName()).isEqualTo("pythonChatModel");
        assertThat(pythonChatModelProvider.getType()).isEqualTo(ResourceType.CHAT_MODEL);

        // Test PythonResourceProvider specific methods
        PythonResourceProvider pythonResourceProvider =
                (PythonResourceProvider) pythonChatModelProvider;
        assertThat(pythonResourceProvider.getClazz()).isEqualTo("TestClazz");
        assertThat(pythonResourceProvider.getModule()).isEqualTo("test.module");
    }

    @Test
    public void testGetResourceFromResourceProvider() throws Exception {
        // Create an agent with resource annotations
        TestAgentWithResources agent = new TestAgentWithResources();
        AgentPlan agentPlan = new AgentPlan(agent);

        // Test getting a tool resource
        Resource myTool = agentPlan.getResource("myTool", ResourceType.TOOL);
        assertThat(myTool).isNotNull();
        assertThat(myTool).isInstanceOf(TestTool.class);
        assertThat(myTool.getResourceType()).isEqualTo(ResourceType.TOOL);

        // Test getting a chat model resource
        Resource chatModel = agentPlan.getResource("chatModel", ResourceType.CHAT_MODEL);
        assertThat(chatModel).isNotNull();
        assertThat(chatModel).isInstanceOf(TestSerializableChatModel.class);
        assertThat(chatModel.getResourceType()).isEqualTo(ResourceType.CHAT_MODEL);

        assertThatThrownBy(() -> agentPlan.getResource("pythonChatModel", ResourceType.CHAT_MODEL))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("PythonResourceAdapter is not set");

        agentPlan.setPythonResourceAdapter(new TestPythonResourceAdapter());
        Resource pythonChatModel =
                agentPlan.getResource("pythonChatModel", ResourceType.CHAT_MODEL);
        assertThat(pythonChatModel).isNotNull();
        assertThat(pythonChatModel).isInstanceOf(TestPythonResource.class);
        assertThat(pythonChatModel.getResourceType()).isEqualTo(ResourceType.CHAT_MODEL);

        // Test that resources are cached (should be the same instance)
        Resource myToolAgain = agentPlan.getResource("myTool", ResourceType.TOOL);
        assertThat(myTool).isSameAs(myToolAgain);
    }

    @Test
    public void testExtractIllegalResourceProviderFromAgent() throws Exception {
        // Create an agent with resource annotations
        TestAgentWithIllegalPythonResource agent = new TestAgentWithIllegalPythonResource();

        // Expect IllegalArgumentException when creating AgentPlan with illegal resource
        assertThatThrownBy(() -> new AgentPlan(agent))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("module should not be null or empty");
    }
}
