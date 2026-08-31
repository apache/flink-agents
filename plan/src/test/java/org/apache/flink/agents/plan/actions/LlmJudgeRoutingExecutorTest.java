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
package org.apache.flink.agents.plan.actions;

import org.apache.flink.agents.api.chat.messages.ChatMessage;
import org.apache.flink.agents.api.chat.messages.MessageRole;
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
                strategy, context, context.getMessages(), new boolean[1]);
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
        String system = messages.get(0).getContent();
        assertTrue(system.contains("big: code and sql"));
        assertTrue(system.contains("{\"model\""));
        assertEquals("USER: write some sql", messages.get(1).getContent());
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
        String userMessage = judgeMessages(Strategies.llm("judge"), context).get(1).getContent();
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
        String userMessage = judgeMessages(Strategies.llm("judge"), context).get(1).getContent();
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
                        .getContent();
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
                        truncated);
        String userMessage = messages.get(1).getContent();
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
                Strategies.llm("judge"), context, context.getMessages(), truncated);
        assertFalse(truncated[0]);
    }
}
