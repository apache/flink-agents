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

package org.apache.flink.agents.api.agents;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.api.InputEvent;
import org.apache.flink.agents.api.chat.messages.ChatMessage;
import org.apache.flink.agents.api.chat.messages.ChatResult;
import org.apache.flink.agents.api.chat.messages.MessageRole;
import org.apache.flink.agents.api.chat.model.BaseChatModelConnection;
import org.apache.flink.agents.api.chat.model.BaseChatModelSetup;
import org.apache.flink.agents.api.chat.model.NativeStructuredOutputSupport;
import org.apache.flink.agents.api.chat.model.StructuredOutputStrategy;
import org.apache.flink.agents.api.chat.model.routing.ModelRouter;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.api.event.ChatRequestEvent;
import org.apache.flink.agents.api.prompt.Prompt;
import org.apache.flink.agents.api.resource.PythonResourceDescriptor;
import org.apache.flink.agents.api.resource.Resource;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.api.skills.SkillSourceSpec;
import org.apache.flink.agents.api.skills.Skills;
import org.apache.flink.agents.api.tools.Tool;
import org.apache.flink.api.common.typeinfo.BasicTypeInfo;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.java.typeutils.RowTypeInfo;
import org.apache.flink.types.Row;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class ReActAgentTest {
    @Test
    public void testOutputSchemaSerialization() throws JsonProcessingException {
        ObjectMapper mapper = new ObjectMapper();
        RowTypeInfo typeInfo =
                new RowTypeInfo(
                        new TypeInformation[] {
                            BasicTypeInfo.INT_TYPE_INFO, BasicTypeInfo.STRING_TYPE_INFO
                        },
                        new String[] {"a", "b"});
        OutputSchema schema = new OutputSchema(typeInfo);
        String json = mapper.writeValueAsString(schema);
        OutputSchema deserialized = mapper.readValue(json, OutputSchema.class);
        Assertions.assertEquals(typeInfo, deserialized.getSchema());
    }

    @Test
    @DisplayName("An agent built on a schema Jackson cannot render reports it with the cause kept")
    public void testAgentRejectsSchemaThatCannotRender() {
        assertThatThrownBy(() -> agentWithSchema(FieldLess.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("FieldLess")
                .hasMessageContaining("cannot be rendered as a JSON Schema")
                .hasCauseInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("An agent built on a self-referential schema reports the self-reference")
    public void testAgentRejectsSelfReferentialSchema() {
        assertThatThrownBy(() -> agentWithSchema(SelfReferential.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SelfReferential")
                .hasMessageContaining("self-referential")
                .hasCauseInstanceOf(StackOverflowError.class);
    }

    @Test
    @DisplayName("An agent built on a member that renders to no properties still prompts with it")
    public void testAgentAcceptsSchemaWithFieldLessMember() {
        assertThat(schemaPromptOf(agentWithSchema(WithCallback.class)))
                .contains("\"count\":{\"type\":\"integer\"}")
                .contains("\"callback\":{\"type\":\"object\",\"properties\":{}}");
    }

    @Test
    @DisplayName("An agent built on a renderable schema prompts with its rendered JSON Schema")
    public void testAgentAcceptsRenderableSchema() {
        assertThat(schemaPromptOf(agentWithSchema(WithCount.class))).contains(WITH_COUNT_SCHEMA);
    }

    @Test
    @DisplayName("An agent built on a class schema registers the native prompt with its schema")
    public void testAgentRegistersNativeSchemaPromptWithRenderedSchema() {
        assertThat(nativeSchemaPromptOf(agentWithSchema(WithCount.class)))
                .isEqualTo(NATIVE_SCHEMA_INSTRUCTION + WITH_COUNT_SCHEMA);
    }

    @Test
    @DisplayName("An agent built on a RowTypeInfo schema registers the native prompt with its text")
    public void testRowTypeSchemaRegistersNativeSchemaPrompt() {
        assertThat(nativeSchemaPromptOf(agentWithSchema(rowTypeInfo())))
                .isEqualTo(NATIVE_SCHEMA_INSTRUCTION + rowTypeInfo());
    }

    @Test
    @DisplayName("An output schema of neither supported kind reports the type it received")
    public void testUnsupportedOutputSchemaTypeReportsTheType() {
        assertThatThrownBy(() -> agentWithSchema("not-a-schema"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("java.lang.String")
                .hasMessageContaining("must be a RowTypeInfo or a Pojo class");
    }

    @Test
    @DisplayName(
            "A RowTypeInfo schema the connection cannot translate keeps the schema instruction")
    public void testRowTypeSchemaKeepsInstruction() throws Exception {
        ReActAgent agent = agentWithSchema(rowTypeInfo());

        ChatRequestEvent request =
                runStart(agent, setup(StructuredOutputStrategy.AUTO, CLASS_SCHEMAS_ONLY));

        assertThat(systemTexts(request)).containsExactly(schemaPromptOf(agent));
    }

    @Test
    @DisplayName("A schema the default chat model applies natively lists its fields without JSON")
    public void testNativelyAppliedSchemaListsFieldsWithoutJson() throws Exception {
        ReActAgent agent = agentWithSchema(WithCount.class);

        ChatRequestEvent request =
                runStart(agent, setup(StructuredOutputStrategy.AUTO, CLASS_SCHEMAS_ONLY));

        assertThat(request.getMessages())
                .extracting(ChatMessage::getRole)
                .containsExactly(MessageRole.SYSTEM, MessageRole.USER);
        assertThat(systemTexts(request))
                .containsExactly(NATIVE_SCHEMA_INSTRUCTION + WITH_COUNT_SCHEMA);
        assertThat(request.getOutputSchema()).isEqualTo(WithCount.class);
    }

    @Test
    @DisplayName("The schema instruction follows the user prompt's first system message")
    public void testSchemaInstructionFollowsTheFirstSystemMessage() throws Exception {
        Prompt prompt =
                Prompt.fromMessages(
                        List.of(
                                new ChatMessage(MessageRole.SYSTEM, "You are a counter."),
                                new ChatMessage(MessageRole.USER, "{input}")));
        ReActAgent agent = agentWithSchema(WithCount.class, prompt);

        ChatRequestEvent request =
                runStart(agent, setup(StructuredOutputStrategy.AUTO, CLASS_SCHEMAS_ONLY));

        assertThat(request.getMessages())
                .extracting(ChatMessage::getRole, ChatMessage::getText)
                .containsExactly(
                        tuple(MessageRole.SYSTEM, "You are a counter."),
                        tuple(MessageRole.SYSTEM, NATIVE_SCHEMA_INSTRUCTION + WITH_COUNT_SCHEMA),
                        tuple(MessageRole.USER, "42"));
    }

    @Test
    @DisplayName("A native answer on a plan without the native prompt adds no instruction")
    public void testNativeGateWithoutNativePromptAddsNoInstruction() throws Exception {
        ReActAgent agent = agentWithSchema(WithCount.class);
        RunnerContext ctx =
                contextFor(agent, setup(StructuredOutputStrategy.AUTO, CLASS_SCHEMAS_ONLY));
        when(ctx.getResource("_default_native_schema_prompt", ResourceType.PROMPT))
                .thenThrow(new IllegalArgumentException("Resource not found"));

        ReActAgent.startAction(new InputEvent(42), ctx);

        ChatRequestEvent request = sentRequest(ctx);
        assertThat(systemTexts(request)).isEmpty();
        assertThat(request.getOutputSchema()).isEqualTo(WithCount.class);
    }

    @Test
    @DisplayName("A gate that throws keeps the instruction and leaves the error to the chat call")
    public void testThrowingGateKeepsInstruction() throws Exception {
        ReActAgent agent = agentWithSchema(rowTypeInfo());

        ChatRequestEvent request =
                runStart(agent, setup(StructuredOutputStrategy.NATIVE, CLASS_SCHEMAS_ONLY));

        assertThat(systemTexts(request)).containsExactly(schemaPromptOf(agent));
        assertThat(request.getOutputSchema()).isInstanceOf(OutputSchema.class);
    }

    @Test
    @DisplayName("An interrupted chat model lookup keeps the instruction and the interrupt status")
    public void testInterruptedLookupRestoresInterruptStatus() throws Exception {
        ReActAgent agent = agentWithSchema(WithCount.class);
        RunnerContext ctx = contextFor(agent, null);
        when(ctx.getResource("_default_chat_model", ResourceType.CHAT_MODEL))
                .thenThrow(new InterruptedException());

        try {
            ReActAgent.startAction(new InputEvent(42), ctx);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
        assertThat(systemTexts(sentRequest(ctx)))
                .anyMatch(text -> text.contains(SCHEMA_INSTRUCTION));
    }

    @Test
    @DisplayName("A cancelled chat model lookup propagates the cancellation")
    public void testCancelledLookupPropagates() throws Exception {
        ReActAgent agent = agentWithSchema(WithCount.class);
        RunnerContext ctx = contextFor(agent, null);
        CancellationException cancellation = new CancellationException();
        when(ctx.getResource("_default_chat_model", ResourceType.CHAT_MODEL))
                .thenThrow(cancellation);

        assertThatThrownBy(() -> ReActAgent.startAction(new InputEvent(42), ctx))
                .isSameAs(cancellation);
    }

    @Test
    @DisplayName("A default chat model that is not a chat model setup keeps the instruction")
    public void testNonSetupChatModelKeepsInstruction() throws Exception {
        ReActAgent agent = agentWithSchema(WithCount.class);

        ChatRequestEvent request = runStart(agent, mock(ModelRouter.class));

        assertThat(systemTexts(request)).anyMatch(text -> text.contains(SCHEMA_INSTRUCTION));
    }

    @Test
    @DisplayName("An agent without a schema sends no instruction and never consults the gate")
    public void testNoSchemaSkipsInstructionAndGate() throws Exception {
        ReActAgent agent = agentWithSchema(null);
        BaseChatModelSetup chatModel = mock(BaseChatModelSetup.class);
        RunnerContext ctx = contextFor(agent, chatModel);

        ReActAgent.startAction(new InputEvent(42), ctx);

        assertThat(systemTexts(sentRequest(ctx))).isEmpty();
        verify(ctx, never()).getResource(eq("_default_chat_model"), any());
        verify(chatModel, never()).willApplyNativeStructuredOutput(any());
    }

    private static final String SCHEMA_INSTRUCTION = "The final response should be json format";

    /** Pinned as a literal, identical to the Python test's, so the two languages cannot drift. */
    private static final String NATIVE_SCHEMA_INSTRUCTION =
            "The final response must include the information for every required field of the"
                    + " following schema. It does not need to be in JSON format. Schema: ";

    private static final String WITH_COUNT_SCHEMA =
            "{\"type\":\"object\",\"properties\":{\"count\":{\"type\":\"integer\"}}}";

    /** Translates a POJO class schema, as the providers do, and no RowTypeInfo schema. */
    private static final NativeStructuredOutputSupport[] CLASS_SCHEMAS_ONLY = {
        NativeStructuredOutputSupport.NATIVE_RECOMMENDED, NativeStructuredOutputSupport.INFEASIBLE
    };

    private static RowTypeInfo rowTypeInfo() {
        return new RowTypeInfo(
                new TypeInformation[] {BasicTypeInfo.INT_TYPE_INFO}, new String[] {"count"});
    }

    private static ChatRequestEvent runStart(ReActAgent agent, Resource chatModel)
            throws Exception {
        RunnerContext ctx = contextFor(agent, chatModel);
        ReActAgent.startAction(new InputEvent(42), ctx);
        return sentRequest(ctx);
    }

    private static RunnerContext contextFor(ReActAgent agent, Resource chatModel) throws Exception {
        Map<String, Object> config = agent.getActions().get("startAction").f2;
        Map<String, Object> prompts = agent.getResources().get(ResourceType.PROMPT);
        RunnerContext ctx = mock(RunnerContext.class);
        when(ctx.getResource("_default_chat_model", ResourceType.CHAT_MODEL)).thenReturn(chatModel);
        when(ctx.getResource("_default_schema_prompt", ResourceType.PROMPT))
                .thenReturn((Resource) prompts.get("_default_schema_prompt"));
        when(ctx.getResource("_default_native_schema_prompt", ResourceType.PROMPT))
                .thenReturn((Resource) prompts.get("_default_native_schema_prompt"));
        when(ctx.getResource("_default_user_prompt", ResourceType.PROMPT))
                .thenReturn((Resource) prompts.get("_default_user_prompt"));
        when(ctx.getActionConfigValue("output_schema")).thenReturn(config.get("output_schema"));
        when(ctx.getActionConfigValue("has_user_prompt")).thenReturn(config.get("has_user_prompt"));
        return ctx;
    }

    private static ChatRequestEvent sentRequest(RunnerContext ctx) {
        ArgumentCaptor<Event> sent = ArgumentCaptor.forClass(Event.class);
        verify(ctx).sendEvent(sent.capture());
        return (ChatRequestEvent) sent.getValue();
    }

    private static List<String> systemTexts(ChatRequestEvent request) {
        return request.getMessages().stream()
                .filter(message -> message.getRole() == MessageRole.SYSTEM)
                .map(ChatMessage::getText)
                .collect(Collectors.toList());
    }

    private static BaseChatModelSetup setup(
            StructuredOutputStrategy strategy, NativeStructuredOutputSupport[] support) {
        return new SchemaSetup(strategy, new SchemaConnection(support[0], support[1]));
    }

    /** Answers native support by schema kind: a class schema, or a RowTypeInfo wrapper. */
    private static class SchemaConnection extends BaseChatModelConnection {
        private final NativeStructuredOutputSupport classSupport;
        private final NativeStructuredOutputSupport rowSupport;

        SchemaConnection(
                NativeStructuredOutputSupport classSupport,
                NativeStructuredOutputSupport rowSupport) {
            super(ResourceDescriptor.Builder.newBuilder("SchemaConnection").build(), null);
            this.classSupport = classSupport;
            this.rowSupport = rowSupport;
        }

        @Override
        protected NativeStructuredOutputSupport supportsNativeStructuredOutput(
                @Nullable Object outputSchema,
                @Nullable List<Tool> tools,
                @Nullable Map<String, Object> modelParams) {
            return outputSchema instanceof Class ? classSupport : rowSupport;
        }

        @Override
        public ChatResult chat(
                List<ChatMessage> messages, List<Tool> tools, Map<String, Object> modelParams) {
            throw new UnsupportedOperationException();
        }
    }

    /** An opened setup bound to the given connection. */
    private static class SchemaSetup extends BaseChatModelSetup {
        SchemaSetup(StructuredOutputStrategy strategy, BaseChatModelConnection connection) {
            super(
                    ResourceDescriptor.Builder.newBuilder("SchemaSetup")
                            .addInitialArgument("structured_output_strategy", strategy)
                            .build(),
                    null);
            this.connection = connection;
        }

        @Override
        public Map<String, Object> getParameters() {
            return new HashMap<>();
        }
    }

    @Test
    @DisplayName("Constructor skills alone become the chat model's skills")
    public void testConstructorOnlySkills() {
        ResourceDescriptor chatModel = descriptor(Map.of("model", "qwen3:8b"));

        ResourceDescriptor actual = chatModelOf(agentWithSkills(chatModel, List.of("a", "b")));

        assertThat(actual.<List<String>>getArgument("skills")).containsExactly("a", "b");
        assertThat(actual.<String>getArgument("model")).isEqualTo("qwen3:8b");
    }

    @Test
    @DisplayName("Without constructor skills the descriptor is used exactly as given")
    public void testDescriptorOnlySkills() {
        ResourceDescriptor chatModel = descriptor(Map.of("skills", List.of("a")));

        assertThat(chatModelOf(agentWithSkills(chatModel, null))).isSameAs(chatModel);
        assertThat(chatModelOf(new ReActAgent(chatModel, null, null))).isSameAs(chatModel);
    }

    @Test
    @DisplayName("An empty skills list adds nothing, so skills stay disabled")
    public void testEmptyConstructorSkillsAddNothing() {
        ResourceDescriptor chatModel = descriptor(Map.of("model", "qwen3:8b"));

        ResourceDescriptor actual = chatModelOf(agentWithSkills(chatModel, List.of()));

        assertThat(actual).isSameAs(chatModel);
        assertThat(actual.getInitialArguments()).doesNotContainKey("skills");
    }

    @Test
    @DisplayName("Descriptor skills come first, then constructor skills, each name kept once")
    public void testCombinedSkillsAreOrderedAndDeduplicated() {
        ResourceDescriptor chatModel = descriptor(Map.of("skills", List.of("a", "b")));

        ResourceDescriptor actual =
                chatModelOf(agentWithSkills(chatModel, List.of("b", "c", "a", "c")));

        assertThat(actual.<List<String>>getArgument("skills")).containsExactly("a", "b", "c");
        assertThat(chatModel.<List<String>>getArgument("skills")).containsExactly("a", "b");
    }

    @Test
    @DisplayName("Agents sharing one descriptor each get only their own constructor skills")
    public void testDescriptorSharedAcrossAgentsStaysIndependent() {
        // Writable collections, as a caller typically builds them, so a merge done in place would
        // show up as one agent's skills leaking into the other instead of an immutable-write error.
        ResourceDescriptor chatModel =
                new ResourceDescriptor(
                        "com.example.ChatModel",
                        new HashMap<>(Map.of("skills", new ArrayList<>(List.of("shared")))));

        ResourceDescriptor first = chatModelOf(agentWithSkills(chatModel, List.of("a")));
        ResourceDescriptor second = chatModelOf(agentWithSkills(chatModel, List.of("b")));

        assertThat(first.<List<String>>getArgument("skills")).containsExactly("shared", "a");
        assertThat(second.<List<String>>getArgument("skills")).containsExactly("shared", "b");
        assertThat(chatModel.<List<String>>getArgument("skills")).containsExactly("shared");
    }

    @Test
    @DisplayName("Constructor skills leave the descriptor's command policy untouched")
    public void testConstructorSkillsKeepAllowedCommands() {
        ResourceDescriptor chatModel =
                descriptor(
                        Map.of(
                                "skills", List.of("a"),
                                "allowed_commands", List.of("echo", "bc"),
                                "allowed_script_dirs", List.of("/opt/scripts")));

        ResourceDescriptor actual = chatModelOf(agentWithSkills(chatModel, List.of("b")));

        assertThat(actual.<List<String>>getArgument("allowed_commands"))
                .containsExactly("echo", "bc");
        assertThat(actual.<List<String>>getArgument("allowed_script_dirs"))
                .containsExactly("/opt/scripts");
    }

    @Test
    @DisplayName("Constructor skills on a Python chat model keep it a Python declaration")
    public void testConstructorSkillsKeepDescriptorLanguage() {
        ResourceDescriptor chatModel =
                PythonResourceDescriptor.Builder.newBuilder("my_module.MyChatModel")
                        .addInitialArgument("skills", List.of("a"))
                        .build();

        ResourceDescriptor actual = chatModelOf(agentWithSkills(chatModel, List.of("b")));

        assertThat(actual)
                .isInstanceOf(PythonResourceDescriptor.class)
                .extracting(ResourceDescriptor::getModule, ResourceDescriptor::getClazz)
                .containsExactly("my_module", "MyChatModel");
        assertThat(actual.<List<String>>getArgument("skills")).containsExactly("a", "b");
    }

    // --- startAction input dispatch ---

    @Test
    @DisplayName("A primitive input without a prompt becomes the user message")
    public void testPrimitiveInputWithoutPromptBecomesUserMessage() {
        RunnerContext ctx = mock(RunnerContext.class);

        ReActAgent.startAction(new InputEvent(42), ctx);

        assertThat(soleUserMessage(ctx).getText()).isEqualTo("42");
    }

    @Test
    @DisplayName("A string input without a prompt becomes the user message")
    public void testStringInputWithoutPromptBecomesUserMessage() {
        RunnerContext ctx = mock(RunnerContext.class);

        ReActAgent.startAction(new InputEvent("analyze this review"), ctx);

        assertThat(soleUserMessage(ctx).getText()).isEqualTo("analyze this review");
    }

    @Test
    @DisplayName("A string input with a prompt fills the {input} placeholder")
    public void testStringInputWithPromptFillsInputPlaceholder() throws Exception {
        RunnerContext ctx = contextWithPrompt(Prompt.fromText("Task: {input}"));

        ReActAgent.startAction(new InputEvent("analyze this review"), ctx);

        assertThat(soleUserMessage(ctx).getText()).isEqualTo("Task: analyze this review");
    }

    @Test
    @DisplayName("A map input without a prompt reports that a prompt is required")
    public void testMapInputWithoutPromptReportsPromptRequirement() {
        RunnerContext ctx = mock(RunnerContext.class);

        assertThatThrownBy(
                        () ->
                                ReActAgent.startAction(
                                        new InputEvent(Map.of("task", "analyze this review")), ctx))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("not primitive types");
    }

    @Test
    @DisplayName("A map input with a prompt uses the keys as placeholders")
    public void testMapInputWithPromptUsesKeysAsPlaceholders() throws Exception {
        RunnerContext ctx = contextWithPrompt(Prompt.fromText("Task: {task}"));

        ReActAgent.startAction(new InputEvent(Map.of("task", "analyze this review")), ctx);

        assertThat(soleUserMessage(ctx).getText()).isEqualTo("Task: analyze this review");
    }

    @Test
    @DisplayName("A Row input with a prompt uses the field names as placeholders")
    public void testRowInputWithPromptUsesFieldNamesAsPlaceholders() throws Exception {
        RunnerContext ctx = contextWithPrompt(Prompt.fromText("Task: {task}"));
        Row row = Row.withNames();
        row.setField("task", "analyze this review");

        ReActAgent.startAction(new InputEvent(row), ctx);

        assertThat(soleUserMessage(ctx).getText()).isEqualTo("Task: analyze this review");
    }

    @Test
    @DisplayName("A Pojo with a non-String field fills placeholders with its stringified value")
    public void testPojoWithNonStringFieldFillsPlaceholdersWithStringifiedValue() throws Exception {
        RunnerContext ctx = contextWithPrompt(Prompt.fromText("Task: {count}"));
        WithCount input = new WithCount();
        input.count = 5;

        ReActAgent.startAction(new InputEvent(input), ctx);

        assertThat(soleUserMessage(ctx).getText()).isEqualTo("Task: 5");
    }

    @Test
    @DisplayName("An input of an unsupported kind reports the accepted kinds")
    public void testInputOfUnsupportedKindReportsAcceptedKinds() throws Exception {
        RunnerContext ctx = contextWithPrompt(Prompt.fromText("Task: {input}"));

        assertThatThrownBy(() -> ReActAgent.startAction(new InputEvent(new FieldLess()), ctx))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Row, Map or Pojo")
                .hasMessageContaining("FieldLess");
    }

    // --- built-in prompt scoping ---

    @Test
    @DisplayName("A configured agent declares its built-in prompts in the start action config")
    public void testConfiguredAgentDeclaresBuiltInPromptsInActionConfig() {
        ReActAgent agent =
                new ReActAgent(
                        chatModelDescriptor(), Prompt.fromText("Task: {input}"), WithCount.class);

        Map<String, Object> config = agent.getActions().get("startAction").f2;
        assertThat(config).containsEntry("has_user_prompt", true);
        assertThat(config).containsEntry("output_schema", WithCount.class);
    }

    @Test
    @DisplayName("A bare agent declares no built-in prompt in the start action config")
    public void testBareAgentDeclaresNoBuiltInPrompt() {
        Map<String, Object> config = bareAgent().getActions().get("startAction").f2;

        assertThat(config).doesNotContainKeys("has_user_prompt", "output_schema");
    }

    @Test
    @DisplayName("A prompt reachable but not declared by this agent is not looked up")
    public void testUndeclaredPromptsAreNotLookedUp() throws Exception {
        // As a sub-agent, a lookup missing locally falls back to the root agent's resources, so
        // a parent ReActAgent's prompts are reachable under the same names.
        RunnerContext ctx = mock(RunnerContext.class);
        when(ctx.getResource("_default_user_prompt", ResourceType.PROMPT))
                .thenReturn(Prompt.fromText("PARENT ONLY: {input}"));
        when(ctx.getResource("_default_schema_prompt", ResourceType.PROMPT))
                .thenReturn(Prompt.fromText("PARENT SCHEMA"));

        ReActAgent.startAction(new InputEvent("review the diff"), ctx);

        assertThat(soleUserMessage(ctx).getText()).isEqualTo("review the diff");
        verify(ctx, never()).getResource(anyString(), any());
    }

    @Test
    @DisplayName("A declared output schema inserts the schema instruction before the user message")
    public void testDeclaredOutputSchemaInsertsSchemaInstruction() throws Exception {
        RunnerContext ctx = mock(RunnerContext.class);
        when(ctx.getActionConfigValue("output_schema")).thenReturn(WithCount.class);
        when(ctx.getResource("_default_schema_prompt", ResourceType.PROMPT))
                .thenReturn(Prompt.fromText("match the schema"));

        ReActAgent.startAction(new InputEvent("review the diff"), ctx);

        ChatRequestEvent request = soleRequest(ctx);
        assertThat(request.getMessages()).hasSize(2);
        assertThat(request.getMessages().get(0).getRole()).isEqualTo(MessageRole.SYSTEM);
        assertThat(request.getMessages().get(0).getText()).isEqualTo("match the schema");
        assertThat(request.getMessages().get(1).getText()).isEqualTo("review the diff");
        assertThat(request.getOutputSchema()).isEqualTo(WithCount.class);
    }

    @Test
    @DisplayName("A declared prompt that cannot be resolved is reported, not dropped")
    public void testDeclaredPromptThatCannotBeResolvedIsReported() throws Exception {
        RunnerContext ctx = mock(RunnerContext.class);
        when(ctx.getActionConfigValue("has_user_prompt")).thenReturn(true);
        when(ctx.getResource("_default_user_prompt", ResourceType.PROMPT))
                .thenThrow(new IllegalArgumentException("Resource not found"));

        assertThatThrownBy(() -> ReActAgent.startAction(new InputEvent("review the diff"), ctx))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("_default_user_prompt")
                .hasCauseInstanceOf(IllegalArgumentException.class);
    }

    // --- single-input map ---

    @Test
    @DisplayName("A single-input map without a prompt passes the value through")
    public void testSingleInputMapWithoutPromptBecomesUserMessage() {
        RunnerContext ctx = mock(RunnerContext.class);

        ReActAgent.startAction(new InputEvent(Map.of("input", "analyze this review")), ctx);

        assertThat(soleUserMessage(ctx).getText()).isEqualTo("analyze this review");
    }

    @Test
    @DisplayName("A multi-key map without a prompt still reports that a prompt is required")
    public void testMultiKeyMapWithoutPromptStillReportsPromptRequirement() {
        RunnerContext ctx = mock(RunnerContext.class);

        assertThatThrownBy(
                        () ->
                                ReActAgent.startAction(
                                        new InputEvent(Map.of("input", "x", "other", "y")), ctx))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("not primitive types");
    }

    private static ReActAgent bareAgent() {
        return new ReActAgent(chatModelDescriptor(), null, null);
    }

    private static ResourceDescriptor chatModelDescriptor() {
        return ResourceDescriptor.Builder.newBuilder("com.example.ChatModel").build();
    }

    private static RunnerContext contextWithPrompt(Prompt prompt) throws Exception {
        RunnerContext ctx = mock(RunnerContext.class);
        when(ctx.getActionConfigValue("has_user_prompt")).thenReturn(true);
        when(ctx.getResource("_default_user_prompt", ResourceType.PROMPT)).thenReturn(prompt);
        return ctx;
    }

    private static ChatRequestEvent soleRequest(RunnerContext ctx) {
        ArgumentCaptor<Event> captor = ArgumentCaptor.forClass(Event.class);
        verify(ctx).sendEvent(captor.capture());
        return (ChatRequestEvent) captor.getValue();
    }

    private static ChatMessage soleUserMessage(RunnerContext ctx) {
        ChatRequestEvent request = soleRequest(ctx);
        assertThat(request.getMessages()).hasSize(1);
        ChatMessage message = request.getMessages().get(0);
        assertThat(message.getRole()).isEqualTo(MessageRole.USER);
        return message;
    }

    private static ReActAgent agentWithSchema(Object outputSchema) {
        return agentWithSchema(outputSchema, null);
    }

    private static ReActAgent agentWithSchema(Object outputSchema, @Nullable Prompt prompt) {
        return new ReActAgent(
                ResourceDescriptor.Builder.newBuilder("com.example.ChatModel").build(),
                prompt,
                outputSchema);
    }

    private static ReActAgent agentWithSkills(
            ResourceDescriptor chatModel, @Nullable List<String> skills) {
        return new ReActAgent(chatModel, null, null, skills);
    }

    /** An immutable argument map, so any write to the caller's descriptor fails the test. */
    private static ResourceDescriptor descriptor(Map<String, Object> arguments) {
        return new ResourceDescriptor("com.example.ChatModel", arguments);
    }

    private static ResourceDescriptor chatModelOf(ReActAgent agent) {
        return (ResourceDescriptor)
                agent.getResources().get(ResourceType.CHAT_MODEL).get("_default_chat_model");
    }

    private static String schemaPromptOf(ReActAgent agent) {
        return promptTextOf(agent, "_default_schema_prompt");
    }

    private static String nativeSchemaPromptOf(ReActAgent agent) {
        return promptTextOf(agent, "_default_native_schema_prompt");
    }

    private static String promptTextOf(ReActAgent agent, String name) {
        Prompt prompt = (Prompt) agent.getResources().get(ResourceType.PROMPT).get(name);
        return prompt.formatString(Map.of());
    }

    @Test
    @DisplayName("Skill source specs are registered as a marker on the chat model descriptor")
    public void testSkillsObjectRegistersSourcesMarker() {
        ResourceDescriptor chatModel = descriptor(Map.of("model", "qwen3:8b"));
        Skills skills = Skills.fromLocalDir("/tmp/skill-a", "/tmp/skill-b");

        ReActAgent agent = new ReActAgent(chatModel, null, null, skills);
        ResourceDescriptor actual = chatModelOf(agent);

        @SuppressWarnings("unchecked")
        List<SkillSourceSpec> sources = actual.getArgument("skills_sources");
        assertThat(sources).isNotNull();
        assertThat(sources).hasSize(2);
        assertThat(sources.get(0).getScheme()).isEqualTo("local");
        assertThat(sources.get(0).getParams()).containsEntry("path", "/tmp/skill-a");
        // The original descriptor is not modified.
        assertThat((Object) chatModel.getArgument("skills_sources")).isNull();
        // The Skills object is registered as a resource.
        assertThat(agent.getResources().get(ResourceType.SKILLS))
                .containsKey("_react_agent_skills");
    }

    @Test
    @DisplayName("Null Skills object adds nothing")
    public void testNullSkillsObjectIsNoOp() {
        ResourceDescriptor chatModel = descriptor(Map.of("model", "qwen3:8b"));

        ReActAgent agent = new ReActAgent(chatModel, null, null, (Skills) null);
        ResourceDescriptor actual = chatModelOf(agent);

        assertThat(actual).isSameAs(chatModel);
        assertThat((Object) actual.getArgument("skills_sources")).isNull();
        assertThat((Object) actual.getArgument("skills")).isNull();
    }

    @Test
    @DisplayName("Empty Skills object adds nothing")
    public void testEmptySkillsObjectIsNoOp() {
        ResourceDescriptor chatModel = descriptor(Map.of("model", "qwen3:8b"));
        Skills empty = new Skills(List.of());

        ReActAgent agent = new ReActAgent(chatModel, null, null, empty);
        ResourceDescriptor actual = chatModelOf(agent);

        assertThat(actual).isSameAs(chatModel);
        assertThat((Object) actual.getArgument("skills_sources")).isNull();
    }

    @Test
    @DisplayName("Skills object and descriptor skills coexist — merge happens at runtime")
    public void testSkillsObjectAndDescriptorSkillsCoexist() {
        ResourceDescriptor chatModel =
                descriptor(Map.of("skills", List.of("a", "b"), "model", "qwen3:8b"));
        Skills skills = Skills.fromLocalDir("/tmp/skill-c");

        ReActAgent agent = new ReActAgent(chatModel, null, null, skills);
        ResourceDescriptor actual = chatModelOf(agent);

        // Explicit skills are preserved on the descriptor.
        assertThat(actual.<List<String>>getArgument("skills")).containsExactly("a", "b");
        // The skills_sources marker is present for runtime resolution.
        @SuppressWarnings("unchecked")
        List<SkillSourceSpec> sources = actual.getArgument("skills_sources");
        assertThat(sources).hasSize(1);
        // The original descriptor is not modified.
        assertThat((Object) chatModel.getArgument("skills_sources")).isNull();
    }

    @Test
    @DisplayName("Skills-object constructor keeps the descriptor language")
    public void testSkillsObjectKeepsDescriptorLanguage() {
        ResourceDescriptor chatModel =
                PythonResourceDescriptor.Builder.newBuilder("my_module.MyChatModel")
                        .addInitialArgument("skills", List.of("a"))
                        .build();
        Skills skills = Skills.fromLocalDir("/tmp/skill-b");

        ReActAgent agent = new ReActAgent(chatModel, null, null, skills);
        ResourceDescriptor actual = chatModelOf(agent);

        assertThat(actual)
                .isInstanceOf(PythonResourceDescriptor.class)
                .extracting(ResourceDescriptor::getModule, ResourceDescriptor::getClazz)
                .containsExactly("my_module", "MyChatModel");
        @SuppressWarnings("unchecked")
        List<SkillSourceSpec> sources = actual.getArgument("skills_sources");
        assertThat(sources).hasSize(1);
    }

    /** A class with no members at all, which Jackson refuses to render rather than rendering. */
    public static class FieldLess {}

    /** A member whose type carries no serializable state, so it renders to an empty object. */
    public static class WithCallback {
        public int count;
        public Function<String, String> callback;
    }

    /** A member that renders to a concrete type. */
    public static class WithCount {
        public int count;
    }

    /** A class reachable from itself, which the generator recurses on until the stack is gone. */
    public static class SelfReferential {
        public String name;
        public SelfReferential next;
    }
}
