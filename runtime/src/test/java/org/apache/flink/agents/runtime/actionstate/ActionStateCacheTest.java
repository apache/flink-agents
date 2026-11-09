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
package org.apache.flink.agents.runtime.actionstate;

import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.api.InputEvent;
import org.apache.flink.agents.plan.actions.Action;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.apache.flink.agents.runtime.actionstate.ActionStateTestUtils.generateKey;
import static org.apache.flink.agents.runtime.utils.TestEvents.occurrence;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Index lifecycle and bounded traversal regressions for the replay cache. */
class ActionStateCacheTest {
    private final Action action = new NoOpAction("action");

    ActionStateCacheTest() throws Exception {}

    @Test
    void genericInputEventsUseTheSameConflictRuleAsJavaInputEvents() throws Exception {
        ActionStateCache cache = new ActionStateCache();
        Event input = occurrence(new Event(InputEvent.EVENT_TYPE, Map.of("input", "value")));
        String first = key("key", 0, action, input);
        cache.put(first, new ActionState(input));
        assertThat(cache.lookup(first, occurrence(new InputEvent("value")), ignored -> {}))
                .isNotNull();
        Event changed = occurrence(new Event(InputEvent.EVENT_TYPE, Map.of("input", "changed")));
        assertThat(cache.lookup(key("key", 0, action, changed), changed, ignored -> {})).isNull();
        assertThat(cache).isEmpty();
    }

    @Test
    void multipleRootActionsShareAnInputFingerprintUntilTheLastIsRemoved() throws Exception {
        ActionStateCache cache = new ActionStateCache();
        Event first = occurrence(new InputEvent("first"));
        Event replacement = occurrence(new InputEvent("replacement"));
        String root1 = key("key", 1, action, first);
        String root2 = key("key", 1, new NoOpAction("second"), first);
        Event child = occurrence(new Event("step"));
        String childKey = key("key", 1, action, child);
        cache.put(root1, new ActionState(first));
        cache.put(root2, new ActionState(first));
        cache.put(childKey, new ActionState(child));
        cache.remove(root1);
        List<String> removed = new ArrayList<>();
        assertThat(cache.lookup(key("key", 1, action, replacement), replacement, removed::add))
                .isNull();
        assertThat(removed).containsExactlyInAnyOrder(root2, childKey);
        assertThat(cache).isEmpty();

        // No obsolete fingerprint may survive invalidation and poison the replacement history.
        String newRoot = key("key", 1, action, replacement);
        cache.put(newRoot, new ActionState(replacement));
        assertThat(cache.lookup(newRoot, occurrence(new InputEvent("replacement")), removed::add))
                .isNotNull();
    }

    @Test
    void outputEvidenceTracksSuccessfulPutsEvenWhenStateIsMutatedInPlace() throws Exception {
        ActionStateCache cache = new ActionStateCache();
        Event root = occurrence(new InputEvent("root"));
        Event oldChild = occurrence(new Event("step", Map.of("value", "same")));
        Event newChild = occurrence(new Event("step", Map.of("value", "same")));
        String rootKey = key("key", 0, action, root);
        String childKey = key("key", 0, action, oldChild);
        ActionState producer = new ActionState(root);
        cache.put(rootKey, producer);
        cache.put(childKey, new ActionState(oldChild));
        producer.addEvent(newChild);
        producer.markCompleted();
        // The object changed, but that is not yet persisted producer evidence.
        assertThat(cache.lookup(childKey, newChild, ignored -> {})).isNotNull();
        cache.put(rootKey, producer);
        assertThat(cache.lookup(childKey, newChild, ignored -> {})).isNull();

        cache.put(childKey, new ActionState(oldChild));
        producer.getOutputEvents().clear();
        cache.put(rootKey, producer);
        // Removing the old evidence must use the saved snapshot, not the mutated producer.
        assertThat(cache.lookup(childKey, newChild, ignored -> {})).isNotNull();
    }

    @Test
    void removingOneProducerDoesNotRemoveAnotherProducersEvidence() throws Exception {
        ActionStateCache cache = new ActionStateCache();
        Event input = occurrence(new InputEvent("input"));
        Event oldChild = occurrence(new Event("step"));
        Event currentChild = occurrence(new Event("step"));
        String first = key("key", 0, action, input);
        String second = key("key", 0, new NoOpAction("second"), input);
        ActionState producer = new ActionState(input);
        producer.addEvent(currentChild);
        producer.markCompleted();
        cache.put(first, producer);
        cache.put(second, producer);
        String child = key("key", 0, action, oldChild);
        cache.put(child, new ActionState(oldChild));
        cache.remove(first);
        assertThat(cache.lookup(child, currentChild, ignored -> {})).isNull();
        cache.put(child, new ActionState(oldChild));
        cache.remove(second);
        assertThat(cache.lookup(child, currentChild, ignored -> {})).isNotNull();
    }

    @Test
    void clearAndTombstoneRemovalDiscardAllEvidence() throws Exception {
        ActionStateCache cache = new ActionStateCache();
        Event old = occurrence(new InputEvent("old"));
        Event current = occurrence(new InputEvent("current"));
        String oldKey = key("key", 0, action, old);
        String currentKey = key("key", 0, action, current);
        for (boolean clear : List.of(false, true)) {
            cache.put(oldKey, new ActionState(old));
            if (clear) {
                cache.clear();
            } else {
                cache.remove(oldKey);
            }
            cache.put(currentKey, new ActionState(current));
            String later = key("key", 1, action, current);
            cache.put(later, new ActionState(current));
            assertThat(cache.lookup(currentKey, current, ignored -> {})).isNotNull();
            assertThat(cache).containsKey(later);
            cache.clear();
        }
    }

    @Test
    void prefixPruningAndSuffixInvalidationHandleSequenceBoundsAndKeepOtherKeys() throws Exception {
        ActionStateCache cache = new ActionStateCache();
        Event event = occurrence(new InputEvent("value"));
        String zero = key("key", 0, action, event);
        String max = key("key", Long.MAX_VALUE, action, event);
        String other = key("other", Long.MAX_VALUE, action, event);
        cache.put(zero, new ActionState(event));
        cache.put(max, new ActionState(event));
        cache.put(other, new ActionState(event));
        String identity = ActionStateUtil.parseKey(zero).get(4);
        assertThat(cache.keysThrough(identity, 0)).containsExactly(zero);
        cache.keysThrough(identity, 0).forEach(cache::remove);
        assertThat(cache.keysThrough(identity, Long.MAX_VALUE)).containsExactly(max);
        Event changed = occurrence(new InputEvent("changed"));
        List<String> removed = new ArrayList<>();
        cache.lookup(key("key", Long.MAX_VALUE, action, changed), changed, removed::add);
        assertThat(removed).containsExactly(max);
        assertThat(cache).containsOnlyKeys(other);
    }

    @Test
    void checkpointRefreshEnumeratesEachKeysRoundsInIncreasingOrder() throws Exception {
        ActionStateCache cache = new ActionStateCache();
        Event event = occurrence(new InputEvent("value"));
        for (String key : List.of("first", "second")) {
            for (long seq : List.of(9L, 0L, 5L)) {
                cache.put(key(key, seq, action, event), new ActionState(event));
            }
        }
        ActionStateCache replayed = new ActionStateCache();
        cache.keysInReplayOrder()
                .forEach(key -> replayed.replay(key, cache.get(key), ignored -> {}));
        assertThat(replayed).containsExactlyInAnyOrderEntriesOf(cache);
    }

    @Test
    void lookupsAndLogReplayNeverTraverseTheFullStateMap() throws Exception {
        NoScanMap states = new NoScanMap();
        ActionStateCache cache = ActionStateCache.wrap(states);
        states.forbidScan = true;
        Event event = occurrence(new InputEvent("value"));
        for (int key = 0; key < 100; key++) {
            for (int seq = 0; seq < 30; seq++) {
                cache.replay(
                        key("key" + key, seq, action, event),
                        new ActionState(event),
                        ignored -> {});
            }
        }
        String hit = key("key0", 10, action, event);
        for (int i = 0; i < 100; i++) {
            assertThat(cache.lookup(hit, event, ignored -> {})).isNotNull();
        }
        Event changed = occurrence(new InputEvent("changed"));
        List<String> removed = new ArrayList<>();
        cache.replay(key("key0", 10, action, changed), new ActionState(changed), removed::add);
        assertThat(removed).hasSize(20);
        assertThat(cache.size()).isEqualTo(2981);
        cache.keysThrough(ActionStateUtil.parseKey(hit).get(4), 9).forEach(cache::remove);
        assertThat(cache.size()).isEqualTo(2971);
    }

    @Test
    void mapViewsCannotBypassIndexMaintenance() throws Exception {
        ActionStateCache cache = new ActionStateCache();
        Event event = occurrence(new InputEvent("value"));
        String key = key("key", 0, action, event);
        cache.put(key, new ActionState(event));
        assertThatThrownBy(() -> cache.keySet().remove(key))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(
                        () -> cache.entrySet().iterator().next().setValue(new ActionState(event)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    private static String key(String key, long seq, Action action, Event event) throws Exception {
        return generateKey(key, seq, action, event, 128);
    }

    /** Structural complexity check, without machine-dependent timing assertions. */
    private static final class NoScanMap extends HashMap<String, ActionState> {
        boolean forbidScan;

        @Override
        public Set<String> keySet() {
            if (forbidScan) throw new AssertionError("full-cache key scan");
            return super.keySet();
        }

        @Override
        public Set<Entry<String, ActionState>> entrySet() {
            if (forbidScan) throw new AssertionError("full-cache entry scan");
            return super.entrySet();
        }

        @Override
        public Collection<ActionState> values() {
            if (forbidScan) throw new AssertionError("full-cache value scan");
            return super.values();
        }

        @Override
        public void forEach(
                java.util.function.BiConsumer<? super String, ? super ActionState> action) {
            if (forbidScan) throw new AssertionError("full-cache forEach scan");
            super.forEach(action);
        }
    }
}
