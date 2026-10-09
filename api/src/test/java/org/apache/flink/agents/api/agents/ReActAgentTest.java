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
import org.apache.flink.agents.api.chat.messages.MessageRole;
import org.apache.flink.agents.api.chat.model.BaseChatModelConnection;
import org.apache.flink.agents.api.chat.model.BaseChatModelSetup;
import org.apache.flink.agents.api.chat.model.NativeStructuredOutputSupport;
import org.apache.flink.agents.api.chat.model.StructuredOutputStrategy;
import org.apache.flink.agents.api.chat.model.routing.ModelRouter;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.api.event.ChatRequestEvent;
import org.apache.flink.agents.api.prompt.Prompt;
import org.apache.flink.agents.api.resource.Resource;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.api.tools.Tool;
import org.apache.flink.api.common.typeinfo.BasicTypeInfo;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.java.typeutils.RowTypeInfo;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import javax.annotation.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
        assertThat(schemaPromptOf(agentWithSchema(WithCount.class)))
                .contains(
                        "{\"type\":\"object\",\"properties\":{\"count\":{\"type\":\"integer\"}}}");
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

        assertThat(systemTexts(request)).anyMatch(text -> text.contains(SCHEMA_INSTRUCTION));
    }

    @Test
    @DisplayName("A schema the default chat model applies natively omits the schema instruction")
    public void testNativelyAppliedSchemaOmitsInstruction() throws Exception {
        ReActAgent agent = agentWithSchema(WithCount.class);

        ChatRequestEvent request =
                runStart(agent, setup(StructuredOutputStrategy.AUTO, CLASS_SCHEMAS_ONLY));

        assertThat(systemTexts(request)).noneMatch(text -> text.contains(SCHEMA_INSTRUCTION));
        assertThat(request.getOutputSchema()).isEqualTo(WithCount.class);
        assertThat(schemaPromptOf(agent)).startsWith(SCHEMA_INSTRUCTION);
    }

    @Test
    @DisplayName("A gate that throws keeps the instruction and leaves the error to the chat call")
    public void testThrowingGateKeepsInstruction() throws Exception {
        ReActAgent agent = agentWithSchema(rowTypeInfo());

        ChatRequestEvent request =
                runStart(agent, setup(StructuredOutputStrategy.NATIVE, CLASS_SCHEMAS_ONLY));

        assertThat(systemTexts(request)).anyMatch(text -> text.contains(SCHEMA_INSTRUCTION));
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
        when(ctx.getActionConfigValue("output_schema")).thenReturn(config.get("output_schema"));
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
        public ChatMessage chat(
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

    private static ReActAgent agentWithSchema(Object outputSchema) {
        return new ReActAgent(
                ResourceDescriptor.Builder.newBuilder("com.example.ChatModel").build(),
                null,
                outputSchema);
    }

    private static String schemaPromptOf(ReActAgent agent) {
        Prompt schemaPrompt =
                (Prompt)
                        agent.getResources().get(ResourceType.PROMPT).get("_default_schema_prompt");
        return schemaPrompt.formatString(Map.of());
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
