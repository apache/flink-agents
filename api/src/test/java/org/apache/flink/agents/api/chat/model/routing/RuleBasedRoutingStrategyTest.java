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

package org.apache.flink.agents.api.chat.model.routing;

import org.apache.flink.agents.api.chat.messages.ChatMessage;
import org.apache.flink.agents.api.chat.messages.MessageRole;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RuleBasedRoutingStrategyTest {

    private static final List<RoutingCandidate> CANDIDATES =
            Arrays.asList(new RoutingCandidate("small"), new RoutingCandidate("big"));

    private RuleBasedRoutingStrategy strategy(Map<String, Object> args) {
        return new RuleBasedRoutingStrategy(
                new ResourceDescriptor(RuleBasedRoutingStrategy.class.getName(), args), null);
    }

    private static String route(RuleBasedRoutingStrategy strategy, String userText) {
        return strategy.route(
                RoutingTestSupport.routingContext(
                        Collections.singletonList(RoutingTestSupport.user(userText)),
                        CANDIDATES,
                        null));
    }

    @Test
    @DisplayName("keyword rule routes to the mapped model (case-insensitive by default)")
    void testKeywordMatch() {
        RuleBasedRoutingStrategy strategy =
                strategy(
                        Map.of(
                                "default",
                                "small",
                                "rules",
                                Collections.singletonList(
                                        Map.of(
                                                "model",
                                                "big",
                                                "keywords",
                                                Arrays.asList("code", "sql")))));

        assertEquals("big", route(strategy, "Please write some CODE for me"));
    }

    @Test
    @DisplayName("no rule match falls back to the default model")
    void testDefaultWhenNoMatch() {
        RuleBasedRoutingStrategy strategy =
                strategy(
                        Map.of(
                                "default",
                                "small",
                                "rules",
                                Collections.singletonList(
                                        Map.of(
                                                "model",
                                                "big",
                                                "keywords",
                                                Collections.singletonList("code")))));

        assertEquals("small", route(strategy, "just saying hello"));
    }

    @Test
    @DisplayName("regex rule matches the request text")
    void testRegexMatch() {
        RuleBasedRoutingStrategy strategy =
                strategy(
                        Map.of(
                                "default",
                                "small",
                                "rules",
                                Collections.singletonList(
                                        Map.of(
                                                "model",
                                                "big",
                                                "regex",
                                                "\\bSELECT\\b.*\\bFROM\\b"))));

        assertEquals("big", route(strategy, "select id from users"));
    }

    @Test
    @DisplayName("rules are evaluated in order; the first match wins")
    void testFirstMatchWins() {
        RuleBasedRoutingStrategy strategy =
                strategy(
                        Map.of(
                                "default",
                                "small",
                                "rules",
                                Arrays.asList(
                                        Map.of(
                                                "model",
                                                "big",
                                                "keywords",
                                                Collections.singletonList("urgent")),
                                        Map.of(
                                                "model",
                                                "small",
                                                "keywords",
                                                Collections.singletonList("urgent")))));

        assertEquals("big", route(strategy, "this is urgent"));
    }

    @Test
    @DisplayName("decision keys on the first user message for tool-call stickiness")
    void testRoutesOnFirstUserMessage() {
        RuleBasedRoutingStrategy strategy =
                strategy(
                        Map.of(
                                "default",
                                "small",
                                "rules",
                                Collections.singletonList(
                                        Map.of(
                                                "model",
                                                "big",
                                                "keywords",
                                                Collections.singletonList("code")))));

        // Later tool-calling round: original "code" request plus assistant/tool messages.
        List<ChatMessage> conversation =
                Arrays.asList(
                        RoutingTestSupport.user("write code"),
                        new ChatMessage(MessageRole.ASSISTANT, "calling a tool"),
                        new ChatMessage(MessageRole.TOOL, "tool result with no keywords"));

        assertEquals(
                "big",
                strategy.route(RoutingTestSupport.routingContext(conversation, CANDIDATES, null)));
    }

    @Test
    @DisplayName("missing default is rejected at construction")
    void testMissingDefaultRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> strategy(Map.of("rules", Collections.emptyList())));
    }
}
