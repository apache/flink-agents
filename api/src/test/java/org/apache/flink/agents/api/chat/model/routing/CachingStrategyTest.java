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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Tests for {@link CachingStrategy}: memoization, LRU eviction, and empty-key passthrough. */
class CachingStrategyTest {

    /** A delegate that counts invocations and echoes the first user message as the choice. */
    private static final class CountingStrategy implements RoutingStrategy {
        int calls = 0;

        @Override
        public String route(RoutingContext context) {
            calls++;
            return "model:" + context.firstUserMessage();
        }
    }

    private static RoutingContext ctxFor(String userText) {
        return RoutingTestSupport.routingContext(
                Collections.singletonList(RoutingTestSupport.user(userText)),
                Collections.emptyList(),
                null);
    }

    @Test
    @DisplayName("same first-user-message is computed once, then served from cache")
    void testMemoizesPerKey() throws Exception {
        CountingStrategy delegate = new CountingStrategy();
        CachingStrategy caching = new CachingStrategy(delegate);

        assertEquals("model:q1", caching.route(ctxFor("q1")));
        assertEquals("model:q1", caching.route(ctxFor("q1")));
        assertEquals("model:q1", caching.route(ctxFor("q1")));
        assertEquals(1, delegate.calls);
    }

    @Test
    @DisplayName("different keys are computed independently")
    void testDifferentKeysRecompute() throws Exception {
        CountingStrategy delegate = new CountingStrategy();
        CachingStrategy caching = new CachingStrategy(delegate);

        caching.route(ctxFor("a"));
        caching.route(ctxFor("b"));
        assertEquals(2, delegate.calls);
    }

    @Test
    @DisplayName("empty first-user-message is never cached")
    void testEmptyKeyNotCached() throws Exception {
        CountingStrategy delegate = new CountingStrategy();
        CachingStrategy caching = new CachingStrategy(delegate);

        caching.route(ctxFor(""));
        caching.route(ctxFor(""));
        assertEquals(2, delegate.calls);
    }

    @Test
    @DisplayName("a null (abstain) decision is not cached and is recomputed next round")
    void testNullDecisionNotCached() throws Exception {
        // Strategy abstains (returns null) on the first call, then commits to a real choice.
        RoutingStrategy flaky =
                new RoutingStrategy() {
                    int calls = 0;

                    @Override
                    public String route(RoutingContext context) {
                        return calls++ == 0 ? null : "model:" + context.firstUserMessage();
                    }
                };
        CachingStrategy caching = new CachingStrategy(flaky);

        assertNull(caching.route(ctxFor("q1"))); // abstained -> must not be cached
        assertEquals("model:q1", caching.route(ctxFor("q1"))); // re-consulted, now decides
        assertEquals("model:q1", caching.route(ctxFor("q1"))); // now served from cache
    }

    @Test
    @DisplayName("bounded LRU evicts the eldest entry past capacity")
    void testLruEviction() throws Exception {
        CountingStrategy delegate = new CountingStrategy();
        CachingStrategy caching = new CachingStrategy(delegate, 2);

        caching.route(ctxFor("a")); // {a}
        caching.route(ctxFor("b")); // {a,b}
        caching.route(ctxFor("c")); // evicts a -> {b,c}
        assertEquals(3, delegate.calls);

        caching.route(ctxFor("a")); // a was evicted -> recompute
        assertEquals(4, delegate.calls);
    }
}
