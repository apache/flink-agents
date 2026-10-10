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

import org.apache.flink.agents.api.chat.messages.AudioBlock;
import org.apache.flink.agents.api.chat.messages.ChatMessage;
import org.apache.flink.agents.api.chat.messages.ContentBlock;
import org.apache.flink.agents.api.chat.messages.DocumentBlock;
import org.apache.flink.agents.api.chat.messages.ImageBlock;
import org.apache.flink.agents.api.chat.messages.MessageRole;
import org.apache.flink.agents.api.chat.messages.ReasoningBlock;
import org.apache.flink.agents.api.chat.messages.TextBlock;
import org.apache.flink.agents.api.chat.messages.ToolCallBlock;
import org.apache.flink.agents.api.chat.messages.ToolResultBlock;
import org.apache.flink.agents.api.chat.messages.VideoBlock;
import org.apache.flink.agents.api.chat.model.routing.RoutingCandidate;
import org.apache.flink.agents.api.chat.model.routing.RoutingContext;
import org.apache.flink.agents.api.chat.model.routing.RoutingStrategy;
import org.apache.flink.agents.api.chat.model.routing.Strategies;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for the judge executor's pure functions (verdict parsing, prompt building). */
class LlmJudgeRoutingExecutorTest {

    private static final List<String> CANDIDATES = List.of("small", "big");

    private static RoutingContext ctx(List<ChatMessage> messages, Map<String, Object> promptArgs) {
        return new RoutingContext(
                UUID.randomUUID(),
                "router",
                messages,
                promptArgs,
                List.of(
                        new RoutingCandidate("small", "cheap chit-chat"),
                        new RoutingCandidate("big", "code and sql")));
    }

    private static List<ChatMessage> judgeMessages(
            RoutingStrategy strategy, RoutingContext context) {
        return LlmJudgeRoutingExecutor.buildJudgeMessages(
                strategy, context, context.getMessages(), java.util.Set.of(), new boolean[1]);
    }

    @Test
    void parseVerdictAcceptsOnlyCandidates() {
        assertEquals(
                Optional.of("big"),
                LlmJudgeRoutingExecutor.parseVerdict("{\"model\": \"big\"}", CANDIDATES));
        assertEquals(
                Optional.of("small"),
                LlmJudgeRoutingExecutor.parseVerdict("  small  ", CANDIDATES));
        // several DISTINCT candidates in JSON form is ambiguous (verdict-first and
        // reasoning-first shapes are mirror images): abstain rather than guess an order
        assertEquals(
                Optional.empty(),
                LlmJudgeRoutingExecutor.parseVerdict(
                        "Not {\"model\": \"small\"} — this needs SQL: {\"model\": \"big\"}",
                        CANDIDATES));
        // repeating the SAME candidate stays unambiguous
        assertEquals(
                Optional.of("big"),
                LlmJudgeRoutingExecutor.parseVerdict(
                        "{\"model\": \"big\"} — yes, {\"model\": \"big\"}.", CANDIDATES));
        // a chatty judge may quote the format contract before answering; scan all matches
        assertEquals(
                Optional.of("big"),
                LlmJudgeRoutingExecutor.parseVerdict(
                        "The format {\"model\": \"<candidate name>\"} means I pick one."
                                + " {\"model\": \"big\"}",
                        CANDIDATES));
        // a verdict naming a non-candidate abstains rather than guessing
        assertEquals(
                Optional.empty(),
                LlmJudgeRoutingExecutor.parseVerdict("{\"model\": \"gpt-attacker\"}", CANDIDATES));
        assertEquals(
                Optional.empty(),
                LlmJudgeRoutingExecutor.parseVerdict("use whichever is cheapest", CANDIDATES));
        assertEquals(Optional.empty(), LlmJudgeRoutingExecutor.parseVerdict(null, CANDIDATES));
    }

    @Test
    void judgeMessagesCarryCandidatesAndVerdictContract() {
        RoutingContext context =
                ctx(List.of(new ChatMessage(MessageRole.USER, "write some sql")), Map.of());
        List<ChatMessage> messages = judgeMessages(Strategies.llm("judge"), context);
        assertEquals(2, messages.size());
        String system = messages.get(0).getText();
        assertTrue(system.contains("big: code and sql"));
        assertTrue(system.contains("{\"model\""));
        assertEquals("USER: write some sql", messages.get(1).getText());
    }

    @Test
    void judgeMessagesIncludeFullConversation() {
        RoutingContext context =
                ctx(
                        List.of(
                                new ChatMessage(MessageRole.SYSTEM, "You review concurrency code"),
                                new ChatMessage(MessageRole.USER, "Focus on race conditions"),
                                new ChatMessage(MessageRole.USER, "synchronized void transfer()")),
                        Map.of());
        String userMessage = judgeMessages(Strategies.llm("judge"), context).get(1).getText();
        assertTrue(userMessage.contains("SYSTEM: You review concurrency code"));
        assertTrue(userMessage.contains("USER: Focus on race conditions"));
        assertTrue(userMessage.contains("USER: synchronized void transfer()"));
    }

    @Test
    void judgeMessagesFallBackToPromptArgsWhenConversationIsEmpty() {
        // With no bound prompt the model ignores promptArgs, but if the conversation carries no
        // text at all the raw args are the only signal available to the judge.
        RoutingContext context =
                ctx(
                        List.of(new ChatMessage(MessageRole.USER, "")),
                        Map.of("input", "write some sql for active users"));
        String userMessage = judgeMessages(Strategies.llm("judge"), context).get(1).getText();
        assertTrue(userMessage.contains("write some sql for active users"));
    }

    @Test
    void templateSubstitutesCandidates() {
        RoutingContext context = ctx(List.of(new ChatMessage(MessageRole.USER, "hi")), Map.of());
        String system =
                judgeMessages(
                                Strategies.llm(
                                        "judge",
                                        "Pick from:\n{candidates}Reply {\"model\": \"...\"}"),
                                context)
                        .get(0)
                        .getText();
        assertTrue(system.contains("- small: cheap chit-chat"));
        assertFalse(system.contains("{candidates}"));
    }

    @Test
    void contextCapPinsSystemAndNewestAndFlagsTruncation() {
        String oldTurn = "y".repeat(500);
        RoutingContext context =
                ctx(
                        List.of(
                                new ChatMessage(MessageRole.SYSTEM, "task framing"),
                                new ChatMessage(MessageRole.USER, oldTurn),
                                new ChatMessage(MessageRole.USER, "current question")),
                        Map.of());
        boolean[] truncated = new boolean[1];
        List<ChatMessage> messages =
                LlmJudgeRoutingExecutor.buildJudgeMessages(
                        Strategies.llm("judge").withMaxContextChars(80),
                        context,
                        context.getMessages(),
                        java.util.Set.of(),
                        truncated);
        String userMessage = messages.get(1).getText();
        assertTrue(truncated[0]);
        assertTrue(userMessage.contains("SYSTEM: task framing"));
        assertTrue(userMessage.contains("USER: current question"));
        assertFalse(userMessage.contains(oldTurn));
    }

    @Test
    void noCapMeansNoTruncation() {
        RoutingContext context =
                ctx(List.of(new ChatMessage(MessageRole.USER, "z".repeat(100_000))), Map.of());
        boolean[] truncated = new boolean[1];
        LlmJudgeRoutingExecutor.buildJudgeMessages(
                Strategies.llm("judge"),
                context,
                context.getMessages(),
                java.util.Set.of(),
                truncated);
        assertFalse(truncated[0]);
    }

    /**
     * Regression (review): the promptArgs fallback must fire when a SYSTEM message coexists with an
     * empty USER message — the conversation is non-empty, but it carries no request text.
     */
    @Test
    void judgeMessagesFallBackToPromptArgsWithSystemAndEmptyUser() {
        RoutingContext context =
                ctx(
                        List.of(
                                new ChatMessage(MessageRole.SYSTEM, "You are a helpful assistant"),
                                new ChatMessage(MessageRole.USER, "")),
                        Map.of("input", "write some sql for active users"));
        String userMessage = judgeMessages(Strategies.llm("judge"), context).get(1).getText();
        assertTrue(userMessage.contains("SYSTEM: You are a helpful assistant"));
        assertTrue(userMessage.contains("write some sql for active users"));
    }

    /**
     * Regression (review): with a cap, messages generated by the anchor's request shaping (the
     * rendered task template — passed as pinned indices) are always kept, like SYSTEM and the
     * newest message.
     */
    @Test
    void contextCapPinsRenderedTemplateMessages() {
        String oldTurn = "y".repeat(500);
        List<ChatMessage> effective =
                List.of(
                        new ChatMessage(
                                MessageRole.USER, "Review this SQL for performance: SELECT 1"),
                        new ChatMessage(MessageRole.USER, oldTurn),
                        new ChatMessage(MessageRole.USER, "current question"));
        RoutingContext context = ctx(effective, Map.of());
        boolean[] truncated = new boolean[1];
        List<ChatMessage> messages =
                LlmJudgeRoutingExecutor.buildJudgeMessages(
                        Strategies.llm("judge").withMaxContextChars(100),
                        context,
                        effective,
                        java.util.Set.of(0), // index 0 = rendered template
                        truncated);
        String userMessage = messages.get(1).getText();
        assertTrue(truncated[0]);
        assertTrue(userMessage.contains("Review this SQL for performance: SELECT 1"));
        assertTrue(userMessage.contains("current question"));
        assertFalse(userMessage.contains(oldTurn));
    }

    @Test
    void judgePreservesInterleavedTextAndAllMediaTypes() {
        List<ContentBlock> content =
                List.of(
                        new TextBlock("before"),
                        ImageBlock.fromBase64("image/png", "aGk="),
                        new TextBlock("between"),
                        AudioBlock.fromUrl("audio/wav", "https://example.com/audio.wav"),
                        VideoBlock.fromBase64("video/mp4", "aGk="),
                        DocumentBlock.fromUrl("application/pdf", "https://example.com/doc.pdf"),
                        new TextBlock("after"));
        ChatMessage message = ChatMessage.user(content);
        ChatMessage input =
                judgeMessages(Strategies.llm("judge"), ctx(List.of(message), Map.of())).get(1);
        assertEquals(new TextBlock("USER: "), input.getBlocks().get(0));
        assertEquals(content, input.getBlocks().subList(1, input.getBlocks().size()));
        assertEquals(content, message.getBlocks());
    }

    @Test
    void judgeExpandsToolResultsAndDescribesCallsWithoutReasoningOrProviderMetadata() {
        ImageBlock image = ImageBlock.fromBase64("image/png", "aGk=");
        ToolCallBlock call =
                new ToolCallBlock(
                        "call-1",
                        "lookup",
                        Map.of("query", "weather"),
                        Map.of("signature", "opaque-call-signature"));
        ToolResultBlock result =
                new ToolResultBlock(
                        "call-1",
                        List.of(new TextBlock("before"), image, new TextBlock("after")),
                        true,
                        Map.of("opaque", "internal-result-metadata"));
        RoutingContext context =
                ctx(
                        List.of(
                                ChatMessage.user("check the result"),
                                ChatMessage.assistant(
                                        List.of(
                                                new ReasoningBlock(
                                                        "private-reasoning",
                                                        Map.of(
                                                                "signature",
                                                                "opaque-reasoning-signature")),
                                                call)),
                                ChatMessage.tool(result)),
                        Map.of());
        ChatMessage input = judgeMessages(Strategies.llm("judge"), context).get(1);
        String text = input.getText();
        assertTrue(text.contains("ASSISTANT: "));
        assertTrue(text.contains("Tool call "));
        assertTrue(text.contains("\"name\":\"lookup\""));
        assertTrue(text.contains("\"input\":{\"query\":\"weather\"}"));
        assertTrue(text.contains("TOOL: Tool result "));
        assertTrue(text.contains("\"call_id\":\"call-1\""));
        assertTrue(text.contains("\"is_error\":true"));
        assertFalse(text.contains("private-reasoning"));
        assertFalse(text.contains("opaque-"));
        assertFalse(text.contains("internal-result-metadata"));
        int imageIndex = input.getBlocks().indexOf(image);
        assertEquals(new TextBlock("before"), input.getBlocks().get(imageIndex - 1));
        assertEquals(new TextBlock("after"), input.getBlocks().get(imageIndex + 1));
        assertTrue(input.getToolCalls().isEmpty());
        assertTrue(
                input.getBlocks().stream()
                        .allMatch(b -> b instanceof TextBlock || b instanceof ImageBlock));
    }

    @Test
    void toolResultTextConsumesBudgetAndMediaIsDroppedWithItsMessage() {
        ImageBlock image = ImageBlock.fromBase64("image/png", "aGk=");
        RoutingContext context =
                ctx(
                        List.of(
                                new ChatMessage(MessageRole.SYSTEM, "framing"),
                                ChatMessage.tool(
                                        new ToolResultBlock(
                                                "old",
                                                List.of(new TextBlock("x".repeat(500)), image),
                                                false)),
                                ChatMessage.user("current question")),
                        Map.of());
        boolean[] truncated = new boolean[1];
        ChatMessage input =
                LlmJudgeRoutingExecutor.buildJudgeMessages(
                                Strategies.llm("judge").withMaxContextChars(80),
                                context,
                                context.getMessages(),
                                java.util.Set.of(),
                                truncated)
                        .get(1);
        assertTrue(truncated[0]);
        assertEquals("SYSTEM: framing\nUSER: current question", input.getText());
        assertFalse(input.getBlocks().contains(image));
    }

    @Test
    void newestToolResultKeepsTextAndMediaEvenWhenItExceedsBudget() {
        ImageBlock image = ImageBlock.fromBase64("image/png", "aGk=");
        String text = "x".repeat(500);
        RoutingContext context =
                ctx(
                        List.of(
                                ChatMessage.user("old request"),
                                ChatMessage.tool(
                                        new ToolResultBlock(
                                                "latest",
                                                List.of(new TextBlock(text), image),
                                                false))),
                        Map.of());
        boolean[] truncated = new boolean[1];
        ChatMessage input =
                LlmJudgeRoutingExecutor.buildJudgeMessages(
                                Strategies.llm("judge").withMaxContextChars(20),
                                context,
                                context.getMessages(),
                                java.util.Set.of(),
                                truncated)
                        .get(1);
        assertTrue(truncated[0]);
        assertTrue(input.getText().contains(text));
        assertFalse(input.getText().contains("old request"));
        assertTrue(input.getBlocks().contains(image));
    }

    @Test
    void mediaOnlyRequestDoesNotFallBackToPromptArgsOrChargePayloadBytes() {
        ImageBlock image = ImageBlock.fromBase64("image/png", "a".repeat(1000));
        RoutingContext context =
                ctx(List.of(ChatMessage.user(List.of(image))), Map.of("input", "unused-args"));
        boolean[] truncated = new boolean[1];
        ChatMessage input =
                LlmJudgeRoutingExecutor.buildJudgeMessages(
                                Strategies.llm("judge").withMaxContextChars(1),
                                context,
                                context.getMessages(),
                                java.util.Set.of(),
                                truncated)
                        .get(1);
        assertEquals(List.of(new TextBlock("USER: "), image), input.getBlocks());
        assertFalse(truncated[0]);
    }
}
