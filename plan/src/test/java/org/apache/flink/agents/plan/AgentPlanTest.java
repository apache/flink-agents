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

import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.api.EventType;
import org.apache.flink.agents.api.InputEvent;
import org.apache.flink.agents.api.OutputEvent;
import org.apache.flink.agents.api.agents.Agent;
import org.apache.flink.agents.api.annotation.ChatModelSetup;
import org.apache.flink.agents.api.annotation.MCPServer;
import org.apache.flink.agents.api.annotation.Tool;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.api.function.PythonFunction;
import org.apache.flink.agents.api.resource.Resource;
import org.apache.flink.agents.api.resource.ResourceContext;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceName;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.api.resource.SerializableResource;
import org.apache.flink.agents.api.resource.python.PythonResourceAdapter;
import org.apache.flink.agents.api.resource.python.PythonResourceWrapper;
import org.apache.flink.agents.api.yaml.YamlLoader;
import org.apache.flink.agents.plan.actions.Action;
import org.apache.flink.agents.plan.resourceprovider.JavaResourceProvider;
import org.apache.flink.agents.plan.resourceprovider.JavaSerializableResourceProvider;
import org.apache.flink.agents.plan.resourceprovider.PythonResourceProvider;
import org.apache.flink.agents.plan.resourceprovider.ResourceProvider;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pemja.core.object.PyObject;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Test for {@link AgentPlan} constructor that takes an Agent. */
public class AgentPlanTest {

    /** Test event class for testing. */
    public static class TestEvent extends Event {
        public static final String EVENT_TYPE = "TestEvent";

        private final String data;

        public TestEvent(String data) {
            super(EVENT_TYPE);
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

    /** Test agent class with annotated methods. */
    public static class TestAgent extends Agent {

        @org.apache.flink.agents.api.annotation.Action(EventType.InputEvent)
        public void handleInputEvent(Event event, RunnerContext context) {
            InputEvent inputEvent = InputEvent.fromEvent(event);
        }

        @org.apache.flink.agents.api.annotation.Action({
            TestEvent.EVENT_TYPE,
            EventType.OutputEvent
        })
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
                    .addInitialArgument("pythonClazz", "test.module.TestClazz")
                    .build();
        }

        @Tool private TestTool anotherTool = new TestTool("anotherTool");

        @org.apache.flink.agents.api.annotation.Action(EventType.InputEvent)
        public void handleInputEvent(Event event, RunnerContext context) {
            InputEvent inputEvent = InputEvent.fromEvent(event);
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

    @Test
    public void testConstructorWithAgent() throws Exception {
        // Create an agent instance
        TestAgent agent = new TestAgent();

        // Create AgentPlan using the new constructor
        AgentPlan agentPlan = new AgentPlan(agent);

        // Verify that actions were collected correctly
        assertThat(agentPlan.getActions().size()).isEqualTo(5);
        assertThat(agentPlan.getActions()).containsKey("handleInputEvent");
        assertThat(agentPlan.getActions()).containsKey("handleMultipleEvents");

        // Verify action details for handleInputEvent
        Action inputAction = agentPlan.getActions().get("handleInputEvent");
        assertThat(inputAction).isNotNull();
        assertThat(inputAction.getName()).isEqualTo("handleInputEvent");
        assertThat(inputAction.getTriggerConditions()).isEqualTo(List.of(InputEvent.EVENT_TYPE));
        assertThat(inputAction.getExec()).isInstanceOf(JavaFunction.class);

        // Check that the JavaFunction instance has the correct method/class/params
        JavaFunction exec = (JavaFunction) inputAction.getExec();
        assertThat(exec.getQualName()).isEqualTo(TestAgent.class.getName());
        assertThat(exec.getMethodName()).isEqualTo("handleInputEvent");
        assertThat(exec.getParameterTypes()).containsExactly(Event.class, RunnerContext.class);

        // Verify action details for handleMultipleEvents
        Action multiAction = agentPlan.getActions().get("handleMultipleEvents");
        assertThat(multiAction).isNotNull();
        assertThat(multiAction.getName()).isEqualTo("handleMultipleEvents");
        assertThat(multiAction.getTriggerConditions())
                .isEqualTo(List.of(TestEvent.EVENT_TYPE, OutputEvent.EVENT_TYPE));
        assertThat(multiAction.getExec()).isInstanceOf(JavaFunction.class);
    }

    @Test
    public void rejectsInvalidConditionsInPlan() {
        for (String[] invalidConditions :
                List.<String[]>of(new String[0], new String[] {"  "}, new String[] {null})) {
            Agent agent = new Agent();
            agent.addAction(
                    "invalid", invalidConditions, new PythonFunction("pkg", "function"), null);

            assertThat(agent.getActions()).containsKey("invalid");
            assertThatThrownBy(() -> new AgentPlan(agent))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    public void preservesYamlActionOrder(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("ordered_actions.yaml");
        Files.writeString(
                file,
                "agents:\n"
                        + "  - name: a\n"
                        + "    actions:\n"
                        + "      - name: first\n"
                        + "        function: pkg.mod:fn\n"
                        + "        trigger_conditions: [input]\n"
                        + "      - name: second\n"
                        + "        function: pkg.mod:fn\n"
                        + "        trigger_conditions: [input]\n"
                        + "      - name: third\n"
                        + "        function: pkg.mod:fn\n"
                        + "        trigger_conditions: [input]\n"
                        + "      - name: fourth\n"
                        + "        function: pkg.mod:fn\n"
                        + "        trigger_conditions: [input]\n");
        Agent agent = YamlLoader.buildAgents(file).getAgents().get("a");

        assertThat(new AgentPlan(agent).getActions().keySet())
                .containsExactly(
                        "chat_model_action",
                        "tool_call_action",
                        "context_retrieval_action",
                        "first",
                        "second",
                        "third",
                        "fourth");
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
        assertThat(agentPlan.getActions().size()).isEqualTo(3);
    }

    @Test
    public void testBuiltInActionsAreJavaNativeAfterCompile() throws Exception {
        AgentPlan agentPlan = new AgentPlan(new Agent() {});

        for (String name :
                List.of("chat_model_action", "tool_call_action", "context_retrieval_action")) {
            Action action = agentPlan.getActions().get(name);
            assertThat(action).isNotNull();
            assertThat(action.getExec()).isInstanceOf(JavaFunction.class);
        }
    }

    /** Cross-language action via {@code @Action(target = @PythonFunction(...))}. */
    public static class AgentWithCrossLanguageAction extends Agent {
        @org.apache.flink.agents.api.annotation.Action(
                value = EventType.InputEvent,
                target =
                        @org.apache.flink.agents.api.annotation.PythonFunction(
                                module = "my_pkg.handlers",
                                qualname = "handle_input"))
        public static void handle(Event event, RunnerContext ctx) {
            throw new UnsupportedOperationException("cross-language stub");
        }
    }

    @Test
    public void testActionWithPythonTargetCompilesToPythonFunctionExec() throws Exception {
        AgentPlan plan = new AgentPlan(new AgentWithCrossLanguageAction());

        Action action = plan.getActions().get("handle");
        assertThat(action).isNotNull();
        assertThat(action.getExec())
                .as("non-empty target.module() must compile to a plan PythonFunction exec")
                .isInstanceOf(org.apache.flink.agents.plan.PythonFunction.class);

        org.apache.flink.agents.plan.PythonFunction exec =
                (org.apache.flink.agents.plan.PythonFunction) action.getExec();
        assertThat(exec.getModule()).isEqualTo("my_pkg.handlers");
        assertThat(exec.getQualName()).isEqualTo("handle_input");
        assertThat(action.getTriggerConditions()).containsExactly(InputEvent.EVENT_TYPE);
    }

    /** Plain {@code @Action} (no {@code target}) compiles to a native Java exec. */
    public static class AgentWithNativeJavaAction extends Agent {
        @org.apache.flink.agents.api.annotation.Action(EventType.InputEvent)
        public static void handle(Event event, RunnerContext ctx) {
            // intentionally empty
        }
    }

    @Test
    public void testActionWithEmptyTargetCompilesToJavaFunctionExec() throws Exception {
        AgentPlan plan = new AgentPlan(new AgentWithNativeJavaAction());

        Action action = plan.getActions().get("handle");
        assertThat(action).isNotNull();
        assertThat(action.getExec())
                .as("empty target.module() must compile to a plan JavaFunction exec")
                .isInstanceOf(JavaFunction.class);
    }

    /** Partially-set target (module without qualname) — must be rejected at compile. */
    public static class AgentWithHalfSetPythonTargetMissingQualname extends Agent {
        @org.apache.flink.agents.api.annotation.Action(
                value = EventType.InputEvent,
                target = @org.apache.flink.agents.api.annotation.PythonFunction(module = "pkg"))
        public static void handle(Event event, RunnerContext ctx) {
            throw new UnsupportedOperationException("cross-language stub");
        }
    }

    @Test
    public void testActionWithPythonTargetMissingQualnameIsRejected() {
        assertThatThrownBy(() -> new AgentPlan(new AgentWithHalfSetPythonTargetMissingQualname()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("handle")
                .hasMessageContaining("qualname");
    }

    /** Partially-set target (qualname without module) — must be rejected at compile. */
    public static class AgentWithHalfSetPythonTargetMissingModule extends Agent {
        @org.apache.flink.agents.api.annotation.Action(
                value = EventType.InputEvent,
                target =
                        @org.apache.flink.agents.api.annotation.PythonFunction(
                                qualname = "handle_input"))
        public static void handle(Event event, RunnerContext ctx) {
            throw new UnsupportedOperationException("cross-language stub");
        }
    }

    @Test
    public void testActionWithPythonTargetMissingModuleIsRejected() {
        assertThatThrownBy(() -> new AgentPlan(new AgentWithHalfSetPythonTargetMissingModule()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("handle")
                .hasMessageContaining("module");
    }

    /**
     * @Action declared on a parent agent class — must be rejected loudly, not silently dropped.
     */
    public abstract static class BaseAgentWithInheritedAction extends Agent {
        @org.apache.flink.agents.api.annotation.Action(EventType.InputEvent)
        public static void sharedAction(Event event, RunnerContext ctx) {
            throw new UnsupportedOperationException("test stub");
        }
    }

    public static class ConcreteAgentInheritingAction extends BaseAgentWithInheritedAction {}

    @Test
    public void testActionInheritedFromParentAgentClassIsRejected() {
        assertThatThrownBy(() -> new AgentPlan(new ConcreteAgentInheritingAction()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sharedAction")
                .hasMessageContaining("BaseAgentWithInheritedAction")
                .hasMessageContaining("Inherited @Action");
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
                        new String[] {InputEvent.EVENT_TYPE},
                        TestAgent.class.getMethod(
                                "handleInputEvent", Event.class, RunnerContext.class))
                .addAction(
                        new String[] {TestEvent.EVENT_TYPE, OutputEvent.EVENT_TYPE},
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
                expectedInputAction.getTriggerConditions(),
                actualInputAction.getTriggerConditions());

        expectedInputAction = expectedPlan.getActions().get("handleMultipleEvents");
        actualInputAction = actualPlan.getActions().get("handleMultipleEvents");
        Assertions.assertEquals(expectedInputAction.getName(), actualInputAction.getName());
        Assertions.assertEquals(expectedInputAction.getExec(), actualInputAction.getExec());
        Assertions.assertEquals(
                expectedInputAction.getTriggerConditions(),
                actualInputAction.getTriggerConditions());
        Assertions.assertEquals(
                123, actualPlan.getActionConfigValue("handleMultipleEvents", "key"));
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
    }

    @Test
    public void testAddResourceRegistersEmbeddingModelProvider() throws Exception {
        Agent agent = new Agent();
        ResourceDescriptor descriptor =
                ResourceDescriptor.Builder.newBuilder(TestPythonResource.class.getName())
                        .addInitialArgument("pythonClazz", "test.module.EmbeddingClazz")
                        .build();
        agent.addResource("myEmbedding", ResourceType.EMBEDDING_MODEL, descriptor);

        AgentPlan plan = new AgentPlan(agent);

        Map<String, ResourceProvider> providers =
                plan.getResourceProviders().get(ResourceType.EMBEDDING_MODEL);
        assertThat(providers).isNotNull();
        assertThat(providers).containsKey("myEmbedding");

        ResourceProvider provider = providers.get("myEmbedding");
        // TestPythonResource implements PythonResourceWrapper, so a Python provider is expected.
        assertThat(provider).isInstanceOf(PythonResourceProvider.class);
        assertThat(provider.getName()).isEqualTo("myEmbedding");
        assertThat(provider.getType()).isEqualTo(ResourceType.EMBEDDING_MODEL);
    }

    @Test
    public void testAddResourceDescriptorWithPythonClazzUsesPythonResourceProvider()
            throws Exception {
        Agent agent = new Agent();
        ResourceDescriptor descriptor =
                ResourceDescriptor.Builder.newBuilder(String.class.getName())
                        .addInitialArgument("pythonClazz", "test.module.EmbeddingClazz")
                        .build();
        agent.addResource("myEmbedding", ResourceType.EMBEDDING_MODEL, descriptor);

        AgentPlan plan = new AgentPlan(agent);

        ResourceProvider provider =
                plan.getResourceProviders().get(ResourceType.EMBEDDING_MODEL).get("myEmbedding");
        assertThat(provider).isInstanceOf(PythonResourceProvider.class);
        assertThat(((PythonResourceProvider) provider).getDescriptor().getClazz())
                .isEqualTo(String.class.getName());
    }

    @Test
    public void testAddResourceRegistersVectorStoreJavaProvider() throws Exception {
        Agent agent = new Agent();
        // A non-Python-wrapper class triggers JavaResourceProvider — String is fine for this
        // structural check; we never call provide() here.
        ResourceDescriptor descriptor =
                ResourceDescriptor.Builder.newBuilder(String.class.getName()).build();
        agent.addResource("myVectorStore", ResourceType.VECTOR_STORE, descriptor);

        AgentPlan plan = new AgentPlan(agent);

        ResourceProvider provider =
                plan.getResourceProviders().get(ResourceType.VECTOR_STORE).get("myVectorStore");
        assertThat(provider).isInstanceOf(JavaResourceProvider.class);
        assertThat(provider.getType()).isEqualTo(ResourceType.VECTOR_STORE);
    }

    @Test
    public void testAddResourceRejectsNonDescriptorForUnsupportedType() {
        Agent agent = new Agent();
        // SerializableResource for EMBEDDING_MODEL is illegal — only PROMPT / TOOL / SKILLS accept
        // non-descriptors. The new code path must reject it with a clear message instead of CCE.
        agent.addResource(
                "badEmbedding",
                ResourceType.EMBEDDING_MODEL,
                new TestSerializableChatModel("badEmbedding"));

        Assertions.assertThrows(IllegalStateException.class, () -> new AgentPlan(agent));
    }

    /** Test agent with descriptor-based Python resource. */
    public static class TestAgentWithDescriptorPythonResource extends Agent {
        @ChatModelSetup
        public static ResourceDescriptor pythonChatModelByDescriptor() {
            return ResourceDescriptor.Builder.newBuilder(String.class.getName())
                    .addInitialArgument("pythonClazz", "test.module.TestClazz")
                    .build();
        }
    }

    @Test
    public void testDescriptorWithPythonClazzUsesPythonResourceProvider() throws Exception {
        AgentPlan plan = new AgentPlan(new TestAgentWithDescriptorPythonResource());

        ResourceProvider provider =
                plan.getResourceProviders()
                        .get(ResourceType.CHAT_MODEL)
                        .get("pythonChatModelByDescriptor");
        assertThat(provider).isInstanceOf(PythonResourceProvider.class);
        assertThat(((PythonResourceProvider) provider).getDescriptor().getClazz())
                .isEqualTo(String.class.getName());
    }

    /** Test agent with explicit Python MCP server declaration. */
    public static class TestAgentWithPythonMCPServer extends Agent {
        @MCPServer(lang = "python")
        public static ResourceDescriptor testMcpServer() {
            return ResourceDescriptor.Builder.newBuilder(ResourceName.MCP_SERVER)
                    .addInitialArgument("endpoint", "http://127.0.0.1:8000/mcp")
                    .addInitialArgument("timeout", 30)
                    .build();
        }
    }

    @Test
    public void testPythonMCPServerUsesPythonResourceProviderAtCompileTime() throws Exception {
        AgentPlan plan = new AgentPlan(new TestAgentWithPythonMCPServer());

        ResourceProvider provider =
                plan.getResourceProviders().get(ResourceType.MCP_SERVER).get("testMcpServer");
        assertThat(provider).isInstanceOf(PythonResourceProvider.class);
    }

    @Test
    public void testAddResourceMCPServerRejectedWithGuidance() {
        Agent agent = new Agent();
        ResourceDescriptor descriptor =
                ResourceDescriptor.Builder.newBuilder("dummy.MCPServer")
                        .addInitialArgument("endpoint", "http://127.0.0.1:0/mcp")
                        .build();
        agent.addResource("addedMcpServer", ResourceType.MCP_SERVER, descriptor);

        UnsupportedOperationException ex =
                Assertions.assertThrows(
                        UnsupportedOperationException.class, () -> new AgentPlan(agent));
        assertThat(ex.getMessage()).contains("@MCPServer");
    }
}
