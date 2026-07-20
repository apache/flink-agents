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

import org.apache.flink.agents.api.resource.Resource;
import org.apache.flink.agents.api.resource.ResourceContext;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LlmRoutingStrategyTest {

    private static final List<RoutingCandidate> CANDIDATES =
            Arrays.asList(
                    new RoutingCandidate("small", "cheap model for chit-chat"),
                    new RoutingCandidate("big", "strong model for code and reasoning"));

    private LlmRoutingStrategy strategy(Map<String, Object> args, ResourceContext ctx) {
        return new LlmRoutingStrategy(
                new ResourceDescriptor(LlmRoutingStrategy.class.getName(), args), ctx);
    }

    private String route(LlmRoutingStrategy strategy, ResourceContext ctx, String userText)
            throws Exception {
        return strategy.route(
                RoutingTestSupport.routingContext(
                        Collections.singletonList(RoutingTestSupport.user(userText)),
                        CANDIDATES,
                        ctx));
    }

    @Test
    @DisplayName("judge model's choice is used when it names a candidate")
    void testJudgeChoiceUsed() throws Exception {
        Map<String, Resource> registry = new HashMap<>();
        registry.put("judge", new RoutingTestSupport.ScriptedJudge("big"));
        ResourceContext ctx = RoutingTestSupport.context(registry);

        assertEquals(
                "big", route(strategy(Map.of("judge_model", "judge"), ctx), ctx, "write code"));
    }

    @Test
    @DisplayName("a verbose judge answer still resolves to the named candidate")
    void testJudgeChoiceParsedFromProse() throws Exception {
        Map<String, Resource> registry = new HashMap<>();
        registry.put(
                "judge",
                new RoutingTestSupport.ScriptedJudge("I think the best choice here is big."));
        ResourceContext ctx = RoutingTestSupport.context(registry);

        assertEquals("big", route(strategy(Map.of("judge_model", "judge"), ctx), ctx, "hi"));
    }

    @Test
    @DisplayName("unrecognized judge answer falls back to the configured default")
    void testFallbackToDefaultOnParseMiss() throws Exception {
        Map<String, Resource> registry = new HashMap<>();
        registry.put("judge", new RoutingTestSupport.ScriptedJudge("no idea"));
        ResourceContext ctx = RoutingTestSupport.context(registry);

        assertEquals(
                "small",
                route(
                        strategy(Map.of("judge_model", "judge", "default", "small"), ctx),
                        ctx,
                        "hi"));
    }

    @Test
    @DisplayName("default is the first candidate when none is configured")
    void testDefaultsToFirstCandidate() throws Exception {
        Map<String, Resource> registry = new HashMap<>();
        registry.put("judge", new RoutingTestSupport.ScriptedJudge("garbage"));
        ResourceContext ctx = RoutingTestSupport.context(registry);

        assertEquals("small", route(strategy(Map.of("judge_model", "judge"), ctx), ctx, "hi"));
    }

    @Test
    @DisplayName("longest candidate name wins when names overlap as substrings")
    void testLongestNameWins() throws Exception {
        List<RoutingCandidate> candidates =
                Arrays.asList(new RoutingCandidate("gpt-4"), new RoutingCandidate("gpt-4o"));
        Map<String, Resource> registry = new HashMap<>();
        registry.put("judge", new RoutingTestSupport.ScriptedJudge("use gpt-4o please"));
        ResourceContext ctx = RoutingTestSupport.context(registry);

        LlmRoutingStrategy strategy = strategy(Map.of("judge_model", "judge"), ctx);
        assertEquals(
                "gpt-4o",
                strategy.route(
                        RoutingTestSupport.routingContext(
                                Collections.singletonList(RoutingTestSupport.user("hi")),
                                candidates,
                                ctx)));
    }

    @Test
    @DisplayName(
            "word-boundary parse does not mis-route a 'gpt-4o-mini' reply to a 'gpt-4' candidate")
    void testSubstringNotMisRouted() throws Exception {
        List<RoutingCandidate> candidates =
                Arrays.asList(new RoutingCandidate("gpt-4"), new RoutingCandidate("claude"));
        Map<String, Resource> registry = new HashMap<>();
        registry.put("judge", new RoutingTestSupport.ScriptedJudge("gpt-4o-mini"));
        ResourceContext ctx = RoutingTestSupport.context(registry);

        // "gpt-4" must NOT match inside "gpt-4o-mini"; with default=claude the reply is unparseable
        // and we fall back to claude rather than mis-routing to gpt-4.
        LlmRoutingStrategy strategy =
                strategy(Map.of("judge_model", "judge", "default", "claude"), ctx);
        assertEquals(
                "claude",
                strategy.route(
                        RoutingTestSupport.routingContext(
                                Collections.singletonList(RoutingTestSupport.user("hi")),
                                candidates,
                                ctx)));
    }

    @Test
    @DisplayName("a transient judge failure abstains (returns null), not the default")
    void testTransientJudgeFailureAbstains() throws Exception {
        // A judge whose chat() throws is a transient failure. The strategy must abstain with null
        // (so the router degrades and a wrapping cache does not pin the conversation to a default),
        // rather than returning the configured default as if it were a real decision.
        Map<String, Resource> registry = new HashMap<>();
        registry.put("judge", new RoutingTestSupport.FailingModel());
        ResourceContext ctx = RoutingTestSupport.context(registry);

        assertNull(
                route(
                        strategy(Map.of("judge_model", "judge", "default", "small"), ctx),
                        ctx,
                        "hi"));
    }

    @Test
    @DisplayName("missing judge_model is rejected at construction")
    void testMissingJudgeRejected() {
        assertThrows(IllegalArgumentException.class, () -> strategy(Collections.emptyMap(), null));
    }
}
