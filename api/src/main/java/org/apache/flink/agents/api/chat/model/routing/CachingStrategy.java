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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A {@link RoutingStrategy} decorator that memoizes the wrapped strategy's decision per
 * conversation (keyed on {@link RoutingContext#firstUserMessage()}), so an expensive selection —
 * e.g. an LLM judge — typically runs once per conversation rather than on every tool-call round.
 *
 * <p>This is <b>best-effort</b> memoization, not a hard guarantee: the lookup and compute are not
 * atomic, so two async-pool threads racing on a key's <i>first</i> touch may both miss and both
 * invoke the delegate (last-writer-wins on the same key). The backing map is synchronized, so there
 * is no corruption, and the redundant compute is benign — hence no locking. Once a value is cached,
 * subsequent rounds are served from it.
 *
 * <p>The cache is a <b>bounded LRU</b> with real eviction (oldest entries are dropped past the
 * capacity), so it never grows without bound and never silently stops caching. Empty keys (requests
 * with no user message) are not cached, to avoid coupling unrelated empty-prompt conversations. A
 * {@code null} decision (the strategy abstaining — e.g. a transient LLM-judge failure) is likewise
 * not cached, so the strategy is re-consulted on the next round rather than pinned to a fallback.
 * Thread-safe for the async execution pool.
 */
public final class CachingStrategy implements RoutingStrategy {

    /** Default cache capacity if none is specified. */
    public static final int DEFAULT_MAX_ENTRIES = 1024;

    private final RoutingStrategy delegate;
    private final Map<String, String> cache;

    public CachingStrategy(RoutingStrategy delegate) {
        this(delegate, DEFAULT_MAX_ENTRIES);
    }

    public CachingStrategy(RoutingStrategy delegate, int maxEntries) {
        if (delegate == null) {
            throw new IllegalArgumentException("delegate strategy must not be null");
        }
        if (maxEntries <= 0) {
            throw new IllegalArgumentException("maxEntries must be positive: " + maxEntries);
        }
        this.delegate = delegate;
        this.cache = Collections.synchronizedMap(new LruMap(maxEntries));
    }

    @Override
    public String route(RoutingContext context) throws Exception {
        String key = context.firstUserMessage();
        if (key.isEmpty()) {
            // Don't cache empty keys: every empty-prompt conversation would otherwise share one
            // decision. Recompute each time instead.
            return delegate.route(context);
        }
        String cached = cache.get(key);
        if (cached != null) {
            return cached;
        }
        String chosen = delegate.route(context);
        if (chosen != null) {
            // Only memoize a real decision. A null is the strategy abstaining ("no opinion", e.g. a
            // transient LLM-judge failure); caching it would pin the whole conversation to the
            // router's default and never re-consult the strategy.
            cache.put(key, chosen);
        }
        return chosen;
    }

    /** The strategy this caches. */
    public RoutingStrategy getDelegate() {
        return delegate;
    }

    /** Bounded access-order LRU map; evicts the eldest entry past {@code maxEntries}. */
    private static final class LruMap extends LinkedHashMap<String, String> {
        private static final long serialVersionUID = 1L;
        private final int maxEntries;

        LruMap(int maxEntries) {
            super(16, 0.75f, true);
            this.maxEntries = maxEntries;
        }

        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            return size() > maxEntries;
        }
    }
}
