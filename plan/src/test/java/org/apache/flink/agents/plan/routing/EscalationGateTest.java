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
package org.apache.flink.agents.plan.routing;

import org.apache.flink.agents.api.chat.messages.ChatMessage;
import org.apache.flink.agents.api.chat.messages.ChatResult;
import org.apache.flink.agents.api.chat.messages.ImageBlock;
import org.apache.flink.agents.api.chat.messages.ReasoningBlock;
import org.apache.flink.agents.api.chat.messages.TextBlock;
import org.apache.flink.agents.api.chat.messages.ToolCallBlock;
import org.apache.flink.agents.api.chat.messages.ToolResultBlock;
import org.apache.flink.agents.api.chat.model.routing.QualityContext;
import org.apache.flink.agents.api.chat.model.routing.QualitySignal;
import org.apache.flink.agents.api.chat.model.routing.QualitySignals;
import org.apache.flink.agents.api.chat.model.routing.QualityVerdict;
import org.apache.flink.agents.api.tools.Tool;
import org.apache.flink.agents.api.tools.ToolMetadata;
import org.apache.flink.agents.api.tools.ToolParameters;
import org.apache.flink.agents.api.tools.ToolResponse;
import org.apache.flink.agents.api.tools.ToolType;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The built-in structural checks and the signal plumbing of {@link EscalationGate}. */
class EscalationGateTest {

    /** A tool with a JSON schema requiring {@code q}; never called. */
    static final class LookupTool extends Tool {
        LookupTool() {
            super(
                    new ToolMetadata(
                            "lookup",
                            "Look something up.",
                            "{\"type\":\"object\",\"properties\":{\"q\":{\"type\":\"string\"}},"
                                    + "\"required\":[\"q\"],\"additionalProperties\":false}"));
        }

        @Override
        public ToolType getToolType() {
            return ToolType.FUNCTION;
        }

        @Override
        public ToolResponse call(ToolParameters parameters) {
            return ToolResponse.text("unused");
        }
    }

    /** A custom signal with a {@code Map} constructor, for the instantiation rule. */
    public static final class ThresholdSignal implements QualitySignal {
        final int minChars;

        public ThresholdSignal(Map<String, Object> args) {
            this.minChars = ((Number) args.getOrDefault("min_chars", 1)).intValue();
        }

        @Override
        public QualityVerdict evaluate(QualityContext context) {
            return context.getResponse().getText().length() < minChars
                    ? QualityVerdict.escalate("too short")
                    : QualityVerdict.accept();
        }
    }

    /** A custom signal with only the no-argument constructor. */
    public static final class AcceptAll implements QualitySignal {
        @Override
        public QualityVerdict evaluate(QualityContext context) {
            return QualityVerdict.accept();
        }
    }

    private static QualityContext context(
            List<ChatMessage> messages, ChatResult response, List<Tool> tools) {
        return new QualityContext(
                UUID.randomUUID(),
                "router",
                "small",
                0,
                List.of("small", "big"),
                messages,
                Map.of(),
                response,
                tools);
    }

    private static ChatResult toolCall(String name, Map<String, Object> input) {
        return new ChatResult(
                ChatMessage.assistant(List.of(new ToolCallBlock("call-1", name, input))));
    }

    @Test
    void aPlainAnswerPasses() {
        QualityVerdict verdict =
                EscalationGate.structural(
                        context(
                                List.of(ChatMessage.user("hi")),
                                new ChatResult(ChatMessage.assistant("hello")),
                                List.of()));
        assertThat(verdict.isEscalate()).isFalse();
    }

    @Test
    void emptyContentWithNoToolCallEscalates() {
        QualityVerdict verdict =
                EscalationGate.structural(
                        context(
                                List.of(ChatMessage.user("hi")),
                                new ChatResult(ChatMessage.assistant("  ")),
                                List.of()));
        assertThat(verdict.isEscalate()).isTrue();
        assertThat(verdict.getReason()).contains("empty response");
    }

    @Test
    void aCallToAToolTheCandidateDoesNotHaveEscalates() {
        QualityVerdict verdict =
                EscalationGate.structural(
                        context(
                                List.of(ChatMessage.user("find it")),
                                toolCall("search", Map.of("q", "x")),
                                List.of(new LookupTool())));
        assertThat(verdict.isEscalate()).isTrue();
        assertThat(verdict.getReason()).contains("'search'").contains("does not have");
    }

    @Test
    void argumentsThatFailTheToolSchemaEscalate() {
        QualityVerdict verdict =
                EscalationGate.structural(
                        context(
                                List.of(ChatMessage.user("find it")),
                                toolCall("lookup", Map.of("query", "x")),
                                List.of(new LookupTool())));
        assertThat(verdict.isEscalate()).isTrue();
        assertThat(verdict.getReason()).contains("fail its schema");
    }

    @Test
    void validArgumentsPassAndAnUnreadableSchemaIsNotHeldAgainstTheCandidate() {
        assertThat(
                        EscalationGate.structural(
                                        context(
                                                List.of(ChatMessage.user("find it")),
                                                toolCall("lookup", Map.of("q", "x")),
                                                List.of(new LookupTool())))
                                .isEscalate())
                .isFalse();
        Tool noSchema =
                new Tool(new ToolMetadata("lookup", "no schema", "")) {
                    @Override
                    public ToolType getToolType() {
                        return ToolType.FUNCTION;
                    }

                    @Override
                    public ToolResponse call(ToolParameters parameters) {
                        return ToolResponse.text("unused");
                    }
                };
        assertThat(
                        EscalationGate.structural(
                                        context(
                                                List.of(ChatMessage.user("find it")),
                                                toolCall("lookup", Map.of("anything", 1)),
                                                List.of(noSchema)))
                                .isEscalate())
                .isFalse();
    }

    @Test
    void theToolChecksAreSkippedWhenTheCandidatesToolsAreNotVisible() {
        // A Python-declared candidate binds its tools on the Python side, so a call to a tool the
        // Java setup cannot see is not held against it; the other checks still apply.
        QualityContext unknownTool =
                context(
                        List.of(ChatMessage.user("find it")),
                        toolCall("search", Map.of("q", "x")),
                        List.of());
        assertThat(EscalationGate.structural(unknownTool, false).isEscalate()).isFalse();
        assertThat(EscalationGate.structural(unknownTool, true).isEscalate()).isTrue();
        QualityContext empty =
                context(
                        List.of(ChatMessage.user("hi")),
                        new ChatResult(ChatMessage.assistant("")),
                        List.of());
        assertThat(EscalationGate.structural(empty, false).isEscalate()).isTrue();
    }

    @Test
    void theSameCallRepeatedInConsecutiveRoundsEscalates() {
        ChatMessage previous =
                ChatMessage.assistant(
                        List.of(new ToolCallBlock("call-0", "lookup", Map.of("q", "x"))));
        List<ChatMessage> messages =
                List.of(
                        ChatMessage.user("find it"),
                        previous,
                        ChatMessage.tool(
                                new org.apache.flink.agents.api.chat.messages.ToolResultBlock(
                                        "call-0",
                                        List.of(
                                                new org.apache.flink.agents.api.chat.messages
                                                        .TextBlock("nothing")),
                                        false)));
        QualityVerdict repeated =
                EscalationGate.structural(
                        context(
                                messages,
                                toolCall("lookup", Map.of("q", "x")),
                                List.of(new LookupTool())));
        assertThat(repeated.isEscalate()).isTrue();
        assertThat(repeated.getReason()).contains("repeated with identical arguments");
        // Different arguments are a new attempt, not a loop.
        QualityVerdict different =
                EscalationGate.structural(
                        context(
                                messages,
                                toolCall("lookup", Map.of("q", "y")),
                                List.of(new LookupTool())));
        assertThat(different.isEscalate()).isFalse();
    }

    @Test
    void theVerifierInputCarriesTheConversationAndTheAnswer() {
        String text =
                EscalationGate.renderForVerifier(
                        context(
                                List.of(ChatMessage.user("write sql")),
                                new ChatResult(ChatMessage.assistant("SELECT 1")),
                                List.of()));
        assertThat(text)
                .startsWith("REQUEST:\n")
                .contains("USER: write sql")
                .contains("\nANSWER:\nSELECT 1");
    }

    @Test
    void aStoredSignalOutcomeSurvivesJsonWithOrWithoutAVerdict() throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper mapper =
                new com.fasterxml.jackson.databind.ObjectMapper();
        EscalationGate.SignalOutcome escalated =
                new EscalationGate.SignalOutcome(QualityVerdict.escalate("too short", 0.2), null);
        String json = mapper.writeValueAsString(escalated);
        EscalationGate.SignalOutcome back =
                mapper.readValue(json, EscalationGate.SignalOutcome.class);
        assertThat(mapper.writeValueAsString(back)).isEqualTo(json);
        assertThat(json).contains("\"too short\"").contains("0.2");

        EscalationGate.SignalOutcome failed = new EscalationGate.SignalOutcome(null, "exploded");
        String failedJson = mapper.writeValueAsString(failed);
        assertThat(
                        mapper.writeValueAsString(
                                mapper.readValue(failedJson, EscalationGate.SignalOutcome.class)))
                .isEqualTo(failedJson);
        assertThat(failedJson).contains("\"exploded\"");
    }

    @Test
    void customSignalsAreInstantiatedWithTheirArgumentsOrTheNoArgConstructor() throws Exception {
        QualitySignal withArgs =
                EscalationGate.instantiate(
                        QualitySignals.custom(
                                ThresholdSignal.class.getName(), Map.of("min_chars", 5)));
        assertThat(((ThresholdSignal) withArgs).minChars).isEqualTo(5);
        QualitySignal noArgs = EscalationGate.instantiate(QualitySignals.custom(AcceptAll.class));
        assertThat(noArgs).isInstanceOf(AcceptAll.class);
        assertThatThrownBy(
                        () ->
                                EscalationGate.instantiate(
                                        QualitySignals.custom(String.class.getName(), Map.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not implement QualitySignal");
    }

    @Test
    void theRepeatCheckIgnoresTheOrderOfArgumentKeys() {
        Map<String, Object> firstFilter = new LinkedHashMap<>();
        firstFilter.put("a", 1);
        firstFilter.put("b", 2);
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("q", "x");
        first.put("filter", firstFilter);
        Map<String, Object> reorderedFilter = new LinkedHashMap<>();
        reorderedFilter.put("b", 2);
        reorderedFilter.put("a", 1);
        Map<String, Object> reordered = new LinkedHashMap<>();
        reordered.put("filter", reorderedFilter);
        reordered.put("q", "x");
        List<ChatMessage> messages =
                List.of(
                        ChatMessage.user("find it"),
                        ChatMessage.assistant(
                                List.of(new ToolCallBlock("call-0", "lookup", first))),
                        ChatMessage.tool(
                                new ToolResultBlock(
                                        "call-0", List.of(new TextBlock("nothing")), false)));
        // The tool checks are off here, so the verdict comes from the repeat check alone.
        QualityVerdict verdict =
                EscalationGate.structural(
                        context(messages, toolCall("lookup", reordered), List.of()), false);
        assertThat(verdict.isEscalate()).isTrue();
        assertThat(verdict.getReason()).contains("repeated with identical arguments");
        Map<String, Object> changed = new LinkedHashMap<>(reordered);
        changed.put("q", "y");
        assertThat(
                        EscalationGate.structural(
                                        context(messages, toolCall("lookup", changed), List.of()),
                                        false)
                                .isEscalate())
                .isFalse();
    }

    @Test
    void anAnswerMadeOfMediaIsNotEmptyButReasoningAloneIs() {
        List<ChatMessage> messages = List.of(ChatMessage.user("draw it"));
        ChatResult picture =
                new ChatResult(
                        ChatMessage.assistant(List.of(ImageBlock.fromBase64("image/png", "aGk="))));
        assertThat(EscalationGate.structural(context(messages, picture, List.of())).isEscalate())
                .isFalse();
        assertThat(EscalationGate.renderForVerifier(context(messages, picture, List.of())))
                .contains("\nANSWER:\n")
                .contains("[image attached]");
        ChatResult thinking =
                new ChatResult(ChatMessage.assistant(List.of(new ReasoningBlock("let me see"))));
        QualityVerdict verdict = EscalationGate.structural(context(messages, thinking, List.of()));
        assertThat(verdict.isEscalate()).isTrue();
        assertThat(verdict.getReason()).contains("empty response");
    }

    @Test
    void theVerifierInputFallsBackToThePromptArgumentsWhenTheRequestHasNoText() {
        QualityContext context =
                new QualityContext(
                        UUID.randomUUID(),
                        "router",
                        "small",
                        0,
                        List.of("small", "big"),
                        List.of(),
                        Map.of("input", "Hello"),
                        new ChatResult(ChatMessage.assistant("Bonjour")),
                        List.of());
        assertThat(EscalationGate.renderForVerifier(context))
                .startsWith("REQUEST:\nRequest arguments:\ninput: Hello")
                .contains("\nANSWER:\nBonjour");
        // System text alone is not the request either; the arguments follow it on their own line.
        QualityContext withSystem =
                new QualityContext(
                        UUID.randomUUID(),
                        "router",
                        "small",
                        0,
                        List.of("small", "big"),
                        List.of(ChatMessage.system("You translate.")),
                        Map.of("input", "Hello"),
                        new ChatResult(ChatMessage.assistant("Bonjour")),
                        List.of());
        assertThat(EscalationGate.renderForVerifier(withSystem))
                .contains("SYSTEM: You translate.\nRequest arguments:\ninput: Hello");
    }
}
