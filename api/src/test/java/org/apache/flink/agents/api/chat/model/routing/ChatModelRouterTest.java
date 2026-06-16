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
import org.apache.flink.agents.api.resource.Resource;
import org.apache.flink.agents.api.resource.ResourceContext;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatModelRouterTest {

    /** A user-supplied strategy, used to verify the bring-your-own extension point. */
    public static class AlwaysBigStrategy extends AbstractRoutingStrategy {
        public AlwaysBigStrategy(ResourceDescriptor descriptor, ResourceContext resourceContext) {
            super(descriptor, resourceContext);
        }

        @Override
        public String route(RoutingContext context) {
            return "big";
        }
    }

    /** A strategy that always names a model that is not a configured candidate (a routing miss). */
    public static class NonCandidateStrategy extends AbstractRoutingStrategy {
        public NonCandidateStrategy(
                ResourceDescriptor descriptor, ResourceContext resourceContext) {
            super(descriptor, resourceContext);
        }

        @Override
        public String route(RoutingContext context) {
            return "does-not-exist";
        }
    }

    /** A strategy that always abstains (returns null / "no opinion"). */
    public static class AbstainStrategy extends AbstractRoutingStrategy {
        public AbstainStrategy(ResourceDescriptor descriptor, ResourceContext resourceContext) {
            super(descriptor, resourceContext);
        }

        @Override
        public String route(RoutingContext context) {
            return null;
        }
    }

    private static ResourceDescriptor ruleStrategy() {
        return ResourceDescriptor.Builder.newBuilder(RuleBasedRoutingStrategy.class.getName())
                .addInitialArgument("default", "small")
                .addInitialArgument(
                        "rules",
                        Collections.singletonList(
                                Map.of(
                                        "model",
                                        "big",
                                        "keywords",
                                        Collections.singletonList("code"))))
                .build();
    }

    private static ChatModelRouter router(
            ResourceDescriptor strategy,
            boolean fallback,
            List<Object> candidates,
            ResourceContext ctx)
            throws Exception {
        ResourceDescriptor descriptor =
                ResourceDescriptor.Builder.newBuilder(ChatModelRouter.class.getName())
                        .addInitialArgument("candidates", candidates)
                        .addInitialArgument("strategy", strategy)
                        .addInitialArgument("fallback", fallback)
                        .build();
        ChatModelRouter router = new ChatModelRouter(descriptor, ctx);
        router.open();
        return router;
    }

    @Test
    @DisplayName("router is a drop-in CHAT_MODEL resource")
    void testResourceTypeIsChatModel() throws Exception {
        ChatModelRouter router =
                router(
                        ruleStrategy(),
                        false,
                        Arrays.asList("small", "big"),
                        RoutingTestSupport.context(new HashMap<>()));
        assertEquals(ResourceType.CHAT_MODEL, router.getResourceType());
    }

    @Test
    @DisplayName("rule-based router delegates to the selected model")
    void testDelegatesToSelectedModel() throws Exception {
        Map<String, Resource> registry = new HashMap<>();
        RoutingTestSupport.RecordingModel small = new RoutingTestSupport.RecordingModel("small");
        RoutingTestSupport.RecordingModel big = new RoutingTestSupport.RecordingModel("big");
        registry.put("small", small);
        registry.put("big", big);
        ResourceContext ctx = RoutingTestSupport.context(registry);

        ChatModelRouter router = router(ruleStrategy(), false, Arrays.asList("small", "big"), ctx);

        ChatMessage response =
                router.chat(
                        Collections.singletonList(RoutingTestSupport.user("please write code")),
                        Collections.emptyMap(),
                        Collections.emptyMap());

        assertEquals("handled-by:big", response.getContent());
        assertEquals(1, big.callCount);
        assertEquals(0, small.callCount);
    }

    @Test
    @DisplayName("non-matching request delegates to the default model")
    void testDelegatesToDefault() throws Exception {
        Map<String, Resource> registry = new HashMap<>();
        RoutingTestSupport.RecordingModel small = new RoutingTestSupport.RecordingModel("small");
        RoutingTestSupport.RecordingModel big = new RoutingTestSupport.RecordingModel("big");
        registry.put("small", small);
        registry.put("big", big);
        ResourceContext ctx = RoutingTestSupport.context(registry);

        ChatModelRouter router = router(ruleStrategy(), false, Arrays.asList("small", "big"), ctx);

        ChatMessage response =
                router.chat(
                        Collections.singletonList(RoutingTestSupport.user("how are you?")),
                        Collections.emptyMap(),
                        Collections.emptyMap());

        assertEquals("handled-by:small", response.getContent());
        assertEquals(1, small.callCount);
        assertEquals(0, big.callCount);
    }

    @Test
    @DisplayName("fallback enabled: a failing primary advances to the next candidate")
    void testFallbackOnError() throws Exception {
        Map<String, Resource> registry = new HashMap<>();
        RoutingTestSupport.FailingModel small = new RoutingTestSupport.FailingModel();
        RoutingTestSupport.RecordingModel big = new RoutingTestSupport.RecordingModel("big");
        registry.put("small", small);
        registry.put("big", big);
        ResourceContext ctx = RoutingTestSupport.context(registry);

        // No rule matches "hello" -> strategy picks default "small" (the failing model).
        ChatModelRouter router = router(ruleStrategy(), true, Arrays.asList("small", "big"), ctx);

        ChatMessage response =
                router.chat(
                        Collections.singletonList(RoutingTestSupport.user("hello")),
                        Collections.emptyMap(),
                        Collections.emptyMap());

        assertEquals("handled-by:big", response.getContent());
        assertEquals(1, small.callCount);
        assertEquals(1, big.callCount);
    }

    @Test
    @DisplayName("fallback disabled: a failing primary surfaces the error")
    void testNoFallbackRethrows() throws Exception {
        Map<String, Resource> registry = new HashMap<>();
        RoutingTestSupport.FailingModel small = new RoutingTestSupport.FailingModel();
        RoutingTestSupport.RecordingModel big = new RoutingTestSupport.RecordingModel("big");
        registry.put("small", small);
        registry.put("big", big);
        ResourceContext ctx = RoutingTestSupport.context(registry);

        ChatModelRouter router = router(ruleStrategy(), false, Arrays.asList("small", "big"), ctx);

        assertThrows(
                RuntimeException.class,
                () ->
                        router.chat(
                                Collections.singletonList(RoutingTestSupport.user("hello")),
                                Collections.emptyMap(),
                                Collections.emptyMap()));
        assertEquals(1, small.callCount);
        assertEquals(0, big.callCount);
    }

    @Test
    @DisplayName("routing is sticky across a simulated tool-calling round")
    void testStickyAcrossToolRound() throws Exception {
        Map<String, Resource> registry = new HashMap<>();
        RoutingTestSupport.RecordingModel small = new RoutingTestSupport.RecordingModel("small");
        RoutingTestSupport.RecordingModel big = new RoutingTestSupport.RecordingModel("big");
        registry.put("small", small);
        registry.put("big", big);
        ResourceContext ctx = RoutingTestSupport.context(registry);

        ChatModelRouter router = router(ruleStrategy(), false, Arrays.asList("small", "big"), ctx);

        // First round: the original "code" request.
        router.chat(
                Collections.singletonList(RoutingTestSupport.user("write code")),
                Collections.emptyMap(),
                Collections.emptyMap());

        // Second round: accumulated conversation (assistant tool call + tool result), as the chat
        // action would re-invoke the router. Must still pick "big".
        List<ChatMessage> conversation =
                Arrays.asList(
                        RoutingTestSupport.user("write code"),
                        new ChatMessage(MessageRole.ASSISTANT, "calling tool"),
                        new ChatMessage(MessageRole.TOOL, "neutral tool output"));
        router.chat(conversation, Collections.emptyMap(), Collections.emptyMap());

        assertEquals(2, big.callCount);
        assertEquals(0, small.callCount);
    }

    @Test
    @DisplayName("a user-supplied strategy plugs in by class name")
    void testBringYourOwnStrategy() throws Exception {
        Map<String, Resource> registry = new HashMap<>();
        RoutingTestSupport.RecordingModel small = new RoutingTestSupport.RecordingModel("small");
        RoutingTestSupport.RecordingModel big = new RoutingTestSupport.RecordingModel("big");
        registry.put("small", small);
        registry.put("big", big);
        ResourceContext ctx = RoutingTestSupport.context(registry);

        ResourceDescriptor custom =
                ResourceDescriptor.Builder.newBuilder(AlwaysBigStrategy.class.getName()).build();
        ChatModelRouter router = router(custom, false, Arrays.asList("small", "big"), ctx);

        ChatMessage response =
                router.chat(
                        Collections.singletonList(RoutingTestSupport.user("anything")),
                        Collections.emptyMap(),
                        Collections.emptyMap());

        assertEquals("handled-by:big", response.getContent());
        assertEquals(1, big.callCount);
    }

    @Test
    @DisplayName(
            "a non-candidate selection is a routing miss: degrade to default (first candidate)")
    void testNonCandidateDegradesToFirstCandidate() throws Exception {
        Map<String, Resource> registry = new HashMap<>();
        RoutingTestSupport.RecordingModel small = new RoutingTestSupport.RecordingModel("small");
        RoutingTestSupport.RecordingModel big = new RoutingTestSupport.RecordingModel("big");
        registry.put("small", small);
        registry.put("big", big);
        ResourceContext ctx = RoutingTestSupport.context(registry);

        ResourceDescriptor custom =
                ResourceDescriptor.Builder.newBuilder(NonCandidateStrategy.class.getName()).build();
        // No "default" configured -> the first candidate ("small") is the default.
        ChatModelRouter router = router(custom, false, Arrays.asList("small", "big"), ctx);

        ChatMessage response =
                router.chat(
                        Collections.singletonList(RoutingTestSupport.user("anything")),
                        Collections.emptyMap(),
                        Collections.emptyMap());

        assertEquals("handled-by:small", response.getContent());
        assertEquals(1, small.callCount);
        assertEquals(0, big.callCount);
    }

    @Test
    @DisplayName("an abstaining (null) strategy degrades to the configured default candidate")
    void testAbstainDegradesToConfiguredDefault() throws Exception {
        Map<String, Resource> registry = new HashMap<>();
        RoutingTestSupport.RecordingModel small = new RoutingTestSupport.RecordingModel("small");
        RoutingTestSupport.RecordingModel big = new RoutingTestSupport.RecordingModel("big");
        registry.put("small", small);
        registry.put("big", big);
        ResourceContext ctx = RoutingTestSupport.context(registry);

        ResourceDescriptor descriptor =
                ResourceDescriptor.Builder.newBuilder(ChatModelRouter.class.getName())
                        .addInitialArgument("candidates", Arrays.asList("small", "big"))
                        .addInitialArgument(
                                "strategy",
                                ResourceDescriptor.Builder.newBuilder(
                                                AbstainStrategy.class.getName())
                                        .build())
                        .addInitialArgument("default", "big")
                        .build();
        ChatModelRouter router = new ChatModelRouter(descriptor, ctx);
        router.open();

        ChatMessage response =
                router.chat(
                        Collections.singletonList(RoutingTestSupport.user("anything")),
                        Collections.emptyMap(),
                        Collections.emptyMap());

        assertEquals("handled-by:big", response.getContent());
        assertEquals(1, big.callCount);
        assertEquals(0, small.callCount);
    }

    @Test
    @DisplayName("a 'default' that is not a configured candidate is rejected at construction")
    void testInvalidDefaultRejected() {
        ResourceContext ctx = RoutingTestSupport.context(new HashMap<>());
        ResourceDescriptor descriptor =
                ResourceDescriptor.Builder.newBuilder(ChatModelRouter.class.getName())
                        .addInitialArgument("candidates", Arrays.asList("small", "big"))
                        .addInitialArgument("strategy", ruleStrategy())
                        .addInitialArgument("default", "nope")
                        .build();
        assertThrows(IllegalArgumentException.class, () -> new ChatModelRouter(descriptor, ctx));
    }

    @Test
    @DisplayName("candidates accept rich {name, description} maps")
    void testRichCandidateSpecs() throws Exception {
        Map<String, Resource> registry = new HashMap<>();
        registry.put("small", new RoutingTestSupport.RecordingModel("small"));
        registry.put("big", new RoutingTestSupport.RecordingModel("big"));
        ResourceContext ctx = RoutingTestSupport.context(registry);

        List<Object> candidates =
                Arrays.asList(
                        Map.of("name", "small", "description", "cheap"),
                        Map.of("name", "big", "description", "strong"));
        ChatModelRouter router = router(ruleStrategy(), false, candidates, ctx);

        assertEquals(2, router.getCandidates().size());
        assertEquals("cheap", router.getCandidates().get(0).getDescription());
    }

    @Test
    @DisplayName("strategy supplied as a deserialized map (post-JSON round-trip) still works")
    void testStrategyFromMapForm() throws Exception {
        Map<String, Resource> registry = new HashMap<>();
        RoutingTestSupport.RecordingModel small = new RoutingTestSupport.RecordingModel("small");
        RoutingTestSupport.RecordingModel big = new RoutingTestSupport.RecordingModel("big");
        registry.put("small", small);
        registry.put("big", big);
        ResourceContext ctx = RoutingTestSupport.context(registry);

        // Mimic what getArgument("strategy") returns after the AgentPlan round-trips through JSON:
        // a plain map with the ResourceDescriptor's JSON field names rather than a typed object.
        Map<String, Object> strategyMap = new HashMap<>();
        strategyMap.put("target_clazz", RuleBasedRoutingStrategy.class.getName());
        strategyMap.put("target_module", "");
        Map<String, Object> rule = new HashMap<>();
        rule.put("model", "big");
        rule.put("keywords", Collections.singletonList("code"));
        Map<String, Object> strategyArgs = new HashMap<>();
        strategyArgs.put("default", "small");
        strategyArgs.put("rules", Collections.singletonList(rule));
        strategyMap.put("arguments", strategyArgs);

        ResourceDescriptor descriptor =
                ResourceDescriptor.Builder.newBuilder(ChatModelRouter.class.getName())
                        .addInitialArgument("candidates", Arrays.asList("small", "big"))
                        .addInitialArgument("strategy", strategyMap)
                        .build();
        ChatModelRouter router = new ChatModelRouter(descriptor, ctx);
        router.open();

        ChatMessage response =
                router.chat(
                        Collections.singletonList(RoutingTestSupport.user("write code")),
                        Collections.emptyMap(),
                        Collections.emptyMap());
        assertEquals("handled-by:big", response.getContent());
    }

    @Test
    @DisplayName("missing candidates or strategy are rejected at construction")
    void testInvalidConfigRejected() {
        ResourceContext ctx = RoutingTestSupport.context(new HashMap<>());

        ResourceDescriptor noCandidates =
                ResourceDescriptor.Builder.newBuilder(ChatModelRouter.class.getName())
                        .addInitialArgument("strategy", ruleStrategy())
                        .build();
        assertThrows(IllegalArgumentException.class, () -> new ChatModelRouter(noCandidates, ctx));

        ResourceDescriptor noStrategy =
                ResourceDescriptor.Builder.newBuilder(ChatModelRouter.class.getName())
                        .addInitialArgument("candidates", Arrays.asList("small", "big"))
                        .build();
        assertThrows(IllegalArgumentException.class, () -> new ChatModelRouter(noStrategy, ctx));
    }

    @Test
    @DisplayName("a null resource context surfaces a clear error at chat() time")
    void testNullResourceContextRejected() throws Exception {
        // Rule-based strategy needs no context; the router is built with a null ResourceContext.
        ChatModelRouter router = router(ruleStrategy(), false, Arrays.asList("small", "big"), null);
        assertThrows(
                IllegalStateException.class,
                () ->
                        router.chat(
                                Collections.singletonList(RoutingTestSupport.user("write code")),
                                Collections.emptyMap(),
                                Collections.emptyMap()));
    }

    @Test
    @DisplayName("a routed candidate is open()-ed before chat() (open-before-chat invariant)")
    void testCandidateOpenedBeforeChat() throws Exception {
        Map<String, Resource> registry = new HashMap<>();
        registry.put("small", new RoutingTestSupport.OpenRequiringModel("small"));
        RoutingTestSupport.OpenRequiringModel big =
                new RoutingTestSupport.OpenRequiringModel("big");
        registry.put("big", big);
        // A context that opens a resource on resolution, like the runtime
        // ResourceCache.getResource()
        // (ResourceCache.java calls resource.open() before returning it). This pins the invariant
        // the
        // router's no-op open() relies on: the chosen candidate is opened before its chat() runs.
        ResourceContext ctx = RoutingTestSupport.openingContext(registry);

        ChatModelRouter router = router(ruleStrategy(), false, Arrays.asList("small", "big"), ctx);
        ChatMessage response =
                router.chat(
                        Collections.singletonList(RoutingTestSupport.user("please write code")),
                        Collections.emptyMap(),
                        Collections.emptyMap());

        assertEquals("handled-by:big", response.getContent());
        assertTrue(big.opened, "the routed candidate must have been opened before chat()");
    }

    @Test
    @DisplayName("the open-before-chat invariant is load-bearing: a non-opened candidate fails")
    void testCandidateNotOpenedFails() throws Exception {
        // Same candidates, but a plain context that does NOT open on resolution -> chat() must
        // fail,
        // proving the invariant above is real and not vacuously satisfied.
        Map<String, Resource> registry = new HashMap<>();
        registry.put("small", new RoutingTestSupport.OpenRequiringModel("small"));
        registry.put("big", new RoutingTestSupport.OpenRequiringModel("big"));
        ResourceContext ctx = RoutingTestSupport.context(registry);

        ChatModelRouter router = router(ruleStrategy(), false, Arrays.asList("small", "big"), ctx);
        assertThrows(
                RuntimeException.class,
                () ->
                        router.chat(
                                Collections.singletonList(
                                        RoutingTestSupport.user("please write code")),
                                Collections.emptyMap(),
                                Collections.emptyMap()));
    }

    @Test
    @DisplayName("connection name is non-null so retry metrics never NPE")
    void testStableConnectionName() throws Exception {
        ChatModelRouter router =
                router(
                        ruleStrategy(),
                        false,
                        Arrays.asList("small", "big"),
                        RoutingTestSupport.context(new HashMap<>()));
        assertTrue(router.getConnectionName() != null && !router.getConnectionName().isEmpty());
    }
}
