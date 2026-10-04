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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.api.InputEvent;
import org.apache.flink.agents.api.chat.messages.ChatMessage;
import org.apache.flink.agents.api.chat.messages.MessageRole;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.api.event.ChatRequestEvent;
import org.apache.flink.agents.api.prompt.Prompt;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.api.common.typeinfo.BasicTypeInfo;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.java.typeutils.RowTypeInfo;
import org.apache.flink.types.Row;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
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

    // --- sub-agent metadata ---

    @Test
    @DisplayName("A bare agent declares the default sub-agent metadata")
    public void testBareAgentDeclaresDefaultSubagentMetadata() throws Exception {
        ReActAgent agent = bareAgent();

        assertThat(agent.getSubagentDescription()).contains("general-purpose");
        // The schema must be well-formed and name the single field the prompt-free map path
        // passes through, or a model-driven call would arrive in a shape the agent rejects.
        JsonNode schema = new ObjectMapper().readTree(agent.getSubagentInputSchema());
        assertThat(schema.get("properties").get("input")).isNotNull();
        assertThat(schema.get("required").toString()).contains("input");
    }

    @Test
    @DisplayName("Declared sub-agent metadata overrides both defaults")
    public void testDeclaredSubagentMetadataOverridesDefaults() {
        ReActAgent agent =
                new ReActAgent(
                        chatModelDescriptor(),
                        null,
                        null,
                        "Reviews pull requests",
                        "{\"type\":\"object\",\"properties\":{\"diff\":{\"type\":\"string\"}}}");

        assertThat(agent.getSubagentDescription()).isEqualTo("Reviews pull requests");
        assertThat(agent.getSubagentInputSchema())
                .isEqualTo("{\"type\":\"object\",\"properties\":{\"diff\":{\"type\":\"string\"}}}");
    }

    @Test
    @DisplayName("A partially declared metadata keeps the other default")
    public void testPartiallyDeclaredMetadataKeepsOtherDefault() throws Exception {
        ReActAgent agent =
                new ReActAgent(chatModelDescriptor(), null, null, "Reviews pull requests", null);

        assertThat(agent.getSubagentDescription()).isEqualTo("Reviews pull requests");
        JsonNode schema = new ObjectMapper().readTree(agent.getSubagentInputSchema());
        assertThat(schema.get("properties").get("input")).isNotNull();
    }

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
        when(ctx.getResource("_default_user_prompt", ResourceType.PROMPT)).thenReturn(prompt);
        return ctx;
    }

    private static ChatMessage soleUserMessage(RunnerContext ctx) {
        ArgumentCaptor<Event> captor = ArgumentCaptor.forClass(Event.class);
        verify(ctx).sendEvent(captor.capture());
        ChatRequestEvent request = (ChatRequestEvent) captor.getValue();
        assertThat(request.getMessages()).hasSize(1);
        ChatMessage message = request.getMessages().get(0);
        assertThat(message.getRole()).isEqualTo(MessageRole.USER);
        return message;
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
