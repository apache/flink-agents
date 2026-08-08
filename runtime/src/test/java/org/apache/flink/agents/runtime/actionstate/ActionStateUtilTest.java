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

import org.apache.flink.agents.api.InputEvent;
import org.apache.flink.agents.plan.actions.Action;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Test class for {@link ActionStateUtil}. */
public class ActionStateUtilTest {

    private static final int MAX_PARALLELISM = 128;

    @Test
    public void testGenerateKeyConsistency() throws Exception {
        // Create test data
        Object key = "consistency-test";
        Action action = new NoOpAction("consistency-action");
        InputEvent inputEvent = new InputEvent("same-input");
        InputEvent inputEvent2 = new InputEvent("same-input");

        // Generate keys multiple times
        String key1 = ActionStateUtil.generateKey(key, 1, action, inputEvent, MAX_PARALLELISM);
        String key2 = ActionStateUtil.generateKey(key, 1, action, inputEvent2, MAX_PARALLELISM);

        // Keys should be the same for the same input
        assertEquals(key1, key2);
    }

    @Test
    public void testGenerateKeyDifferentInputs() throws Exception {
        // Create test data
        Object key = "diff-test";
        Action action = new NoOpAction("diff-action");
        InputEvent inputEvent1 = new InputEvent("input1");
        InputEvent inputEvent2 = new InputEvent("input2");

        // Generate keys
        String key1 = ActionStateUtil.generateKey(key, 1, action, inputEvent1, MAX_PARALLELISM);
        String key2 = ActionStateUtil.generateKey(key, 1, action, inputEvent2, MAX_PARALLELISM);

        // Keys should be different for different inputs
        assertNotEquals(key1, key2);
    }

    @Test
    public void testGenerateKeyWithNullKey() throws Exception {
        Action action = new NoOpAction("test-action");
        InputEvent inputEvent = new InputEvent("test-input");

        assertThrows(
                NullPointerException.class,
                () -> {
                    ActionStateUtil.generateKey(null, 1, action, inputEvent, MAX_PARALLELISM);
                });
    }

    @Test
    public void testGenerateKeyWithNullAction() {
        Object key = "test-key";
        InputEvent inputEvent = new InputEvent("test-input");

        assertThrows(
                NullPointerException.class,
                () -> {
                    ActionStateUtil.generateKey(key, 1, null, inputEvent, MAX_PARALLELISM);
                });
    }

    @Test
    public void testGenerateKeyWithNullEvent() throws Exception {
        Object key = "test-key";
        Action action = new NoOpAction("test-action");

        assertThrows(
                NullPointerException.class,
                () -> {
                    ActionStateUtil.generateKey(key, 1, action, null, MAX_PARALLELISM);
                });
    }

    @Test
    public void testGenerateKeyRejectsNonPositiveMaxParallelism() throws Exception {
        Object key = "test-key";
        Action action = new NoOpAction("test-action");
        InputEvent inputEvent = new InputEvent("test-input");

        assertThrows(
                IllegalArgumentException.class,
                () -> ActionStateUtil.generateKey(key, 1, action, inputEvent, 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> ActionStateUtil.generateKey(key, 1, action, inputEvent, -1));
    }

    /**
     * The action-UUID key segment must be derived from the plan-unique action NAME, never from
     * {@code Action.hashCode()}: the hash folds in {@code Class.hashCode()} (a per-JVM identity
     * hash), so a hash-derived segment silently changes across process restarts and recovery
     * lookups can never hit. This pins the derivation so any future change to the key format is a
     * conscious, reviewed break of cross-restart state compatibility.
     */
    @Test
    public void testActionUUIDSegmentDerivesFromActionName() throws Exception {
        Action action = new NoOpAction("test-action");
        String generatedKey =
                ActionStateUtil.generateKey(
                        "test-key", 1, action, new InputEvent("test-input"), MAX_PARALLELISM);

        String actionUUIDSegment = ActionStateUtil.parseKey(generatedKey).get(3);
        assertEquals(
                UUID.nameUUIDFromBytes("test-action".getBytes(StandardCharsets.UTF_8)).toString(),
                actionUUIDSegment);
    }

    /**
     * Two separately constructed Action instances with the same name — which is what "the same
     * action, after a JVM restart" looks like — must produce identical state keys, or recovery can
     * never replay.
     */
    @Test
    public void testSameActionNameYieldsSameKeyAcrossInstances() throws Exception {
        InputEvent event = new InputEvent("test-input");
        String first =
                ActionStateUtil.generateKey(
                        "test-key", 7, new NoOpAction("stable-name"), event, MAX_PARALLELISM);
        String second =
                ActionStateUtil.generateKey(
                        "test-key", 7, new NoOpAction("stable-name"), event, MAX_PARALLELISM);
        assertEquals(first, second);
    }

    @Test
    public void testParseKeyValidKey() throws Exception {
        // Create test data and generate a key
        Object key = "test-key";
        Action action = new NoOpAction("test-action");
        InputEvent inputEvent = new InputEvent("test-input");
        long seqNum = 123;

        String generatedKey =
                ActionStateUtil.generateKey(key, seqNum, action, inputEvent, MAX_PARALLELISM);

        // Parse the generated key
        List<String> parsedParts = ActionStateUtil.parseKey(generatedKey);

        // Verify the parsed components: [keyGroup, seqNum, eventUUID, actionUUID, businessKey]
        assertEquals(5, parsedParts.size());
        assertTrue(Integer.parseInt(parsedParts.get(0)) >= 0); // keyGroup
        assertEquals(String.valueOf(seqNum), parsedParts.get(1));
        // The event and action UUID segments are non-empty.
        assertTrue(parsedParts.get(2).length() > 0);
        assertTrue(parsedParts.get(3).length() > 0);
        assertEquals(key.toString(), parsedParts.get(4));
    }

    @Test
    public void testParseKeyRoundTrip() throws Exception {
        // Test that generate -> parse -> values match original inputs
        Object originalKey = "round-trip-test";
        Action action = new NoOpAction("round-trip-action");
        InputEvent inputEvent = new InputEvent("round-trip-input");
        long seqNum = 456;

        String generatedKey =
                ActionStateUtil.generateKey(
                        originalKey, seqNum, action, inputEvent, MAX_PARALLELISM);
        List<String> parsedParts = ActionStateUtil.parseKey(generatedKey);

        assertEquals(originalKey.toString(), parsedParts.get(4));
        assertEquals(String.valueOf(seqNum), parsedParts.get(1));
    }

    @Test
    public void testParseKeyWithNullInput() {
        assertThrows(
                NullPointerException.class,
                () -> {
                    ActionStateUtil.parseKey(null);
                });
    }

    @Test
    public void testParseKeyWithInvalidFormat() {
        // Too few segments.
        assertThrows(
                IllegalArgumentException.class,
                () -> {
                    ActionStateUtil.parseKey("only_three_parts");
                });

        // Still one segment short of the required count.
        assertThrows(
                IllegalArgumentException.class,
                () -> {
                    ActionStateUtil.parseKey("one_two_three_four");
                });

        // Empty string.
        assertThrows(
                IllegalArgumentException.class,
                () -> {
                    ActionStateUtil.parseKey("");
                });
    }

    @Test
    public void testParseKeyWithSpecialCharacters() throws Exception {
        // Test with keys containing special characters (but not the separator)
        Object key = "key-with-special@chars#123";
        Action action = new NoOpAction("action-with-special@chars");
        InputEvent inputEvent = new InputEvent("input-with-special@chars");
        long seqNum = 789;

        String generatedKey =
                ActionStateUtil.generateKey(key, seqNum, action, inputEvent, MAX_PARALLELISM);
        List<String> parsedParts = ActionStateUtil.parseKey(generatedKey);

        assertEquals(key.toString(), parsedParts.get(4));
        assertEquals(String.valueOf(seqNum), parsedParts.get(1));
    }

    @Test
    public void testParseKeyConsistencyWithDifferentKeys() throws Exception {
        // Generate keys with different inputs and verify parsing consistency
        Action action = new NoOpAction("consistency-action");
        InputEvent inputEvent = new InputEvent("consistency-input");

        String key1 = ActionStateUtil.generateKey("key1", 100, action, inputEvent, MAX_PARALLELISM);
        String key2 = ActionStateUtil.generateKey("key2", 200, action, inputEvent, MAX_PARALLELISM);

        List<String> parsed1 = ActionStateUtil.parseKey(key1);
        List<String> parsed2 = ActionStateUtil.parseKey(key2);

        // Business keys and sequence numbers differ.
        assertNotEquals(parsed1.get(4), parsed2.get(4)); // businessKey
        assertNotEquals(parsed1.get(1), parsed2.get(1)); // seqNum

        // But event and action UUIDs should be the same (same event and action)
        assertEquals(parsed1.get(2), parsed2.get(2)); // Event UUID
        assertEquals(parsed1.get(3), parsed2.get(3)); // Action UUID
    }

    @Test
    public void testIsKeyRetainedFiltersForeignKeys() throws Exception {
        Action action = new NoOpAction("owner-action");
        InputEvent event = new InputEvent("owner-input");
        String ownedKey = ActionStateUtil.generateKey("A", 1, action, event, MAX_PARALLELISM);
        String foreignKey = ActionStateUtil.generateKey("B", 1, action, event, MAX_PARALLELISM);

        int ownedKeyGroup = ActionStateUtil.parseKeyGroup(ownedKey);
        assertTrue(ActionStateUtil.isKeyRetained(kg -> kg == ownedKeyGroup, ownedKey));
        assertFalse(ActionStateUtil.isKeyRetained(kg -> kg == ownedKeyGroup, foreignKey));
    }

    @Test
    public void testIsKeyRetainedKeepsAllKeysWhenNoFilter() throws Exception {
        Action action = new NoOpAction("no-filter-action");
        InputEvent event = new InputEvent("no-filter-input");
        String keyA = ActionStateUtil.generateKey("A", 1, action, event, MAX_PARALLELISM);
        String keyB = ActionStateUtil.generateKey("B", 1, action, event, MAX_PARALLELISM);

        assertTrue(ActionStateUtil.isKeyRetained(null, keyA));
        assertTrue(ActionStateUtil.isKeyRetained(null, keyB));
    }

    @Test
    public void testIsKeyRetainedDropsUnrecognizedFormatKeys() {
        // Keys that do not have the current segment count cannot be attributed to a key-group, so
        // they are dropped during ownership filtering rather than retained in every subtask. This
        // closes the orphan-state leak; the project does not preserve pre-format durable state.
        assertFalse(ActionStateUtil.isKeyRetained(kg -> true, "test-key_1_event-uuid_action-uuid"));
        assertFalse(ActionStateUtil.isKeyRetained(kg -> true, "malformed-key"));
    }

    @Test
    public void testIsKeyRetainedDropsKeyWithUnparsableKeyGroup() {
        // A well-formed (5-segment) key whose key-group segment is not numeric cannot be
        // attributed to a key-group and is dropped.
        assertFalse(
                ActionStateUtil.isKeyRetained(
                        kg -> true, "not-a-number_1_event-uuid_action-uuid_bkey"));
    }

    @Test
    public void testBusinessKeyContainingSeparatorIsHandled() throws Exception {
        // A business key containing the separator (e.g. "tenant_user") must still round-trip and
        // be attributable, because it occupies the trailing segment of the composite key. This is
        // the exact case that broke the previous segment-count parsing.
        Object businessKey = "tenant_user";
        Action action = new NoOpAction("underscore-action");
        InputEvent event = new InputEvent("underscore-input");
        String stateKey =
                ActionStateUtil.generateKey(businessKey, 3, action, event, MAX_PARALLELISM);

        assertEquals("tenant_user", ActionStateUtil.businessKeyOf(stateKey));
        assertEquals("tenant_user", ActionStateUtil.parseKey(stateKey).get(4));
        assertTrue(ActionStateUtil.matchesBusinessKey(stateKey, businessKey));
        assertTrue(ActionStateUtil.matchesBusinessKeyAndSeqNum(stateKey, businessKey, 3));

        int ownedKeyGroup = ActionStateUtil.parseKeyGroup(stateKey);
        assertTrue(ActionStateUtil.isKeyRetained(kg -> kg == ownedKeyGroup, stateKey));
        assertFalse(ActionStateUtil.isKeyRetained(kg -> kg != ownedKeyGroup, stateKey));
    }

    @Test
    public void testMatchesBusinessKeyIsSegmentExact() throws Exception {
        Action action = new NoOpAction("match-action");
        InputEvent event = new InputEvent("match-input");
        // Numeric business key 1 at seqNum 5: a substring match on "_5_" would wrongly
        // attribute this record to business key 5 via its seqNum segment.
        String keyOneAtSeqFive = ActionStateUtil.generateKey(1L, 5, action, event, MAX_PARALLELISM);

        assertTrue(ActionStateUtil.matchesBusinessKey(keyOneAtSeqFive, 1L));
        assertFalse(ActionStateUtil.matchesBusinessKey(keyOneAtSeqFive, 5L));
        assertFalse(ActionStateUtil.matchesBusinessKey("legacy_1_event-uuid_action-uuid", 1L));
    }

    @Test
    public void testMatchesBusinessKeyAndSeqNum() throws Exception {
        Action action = new NoOpAction("match-action");
        InputEvent event = new InputEvent("match-input");
        String stateKey = ActionStateUtil.generateKey("A", 7, action, event, MAX_PARALLELISM);

        assertTrue(ActionStateUtil.matchesBusinessKeyAndSeqNum(stateKey, "A", 7));
        assertFalse(ActionStateUtil.matchesBusinessKeyAndSeqNum(stateKey, "A", 8));
        assertFalse(ActionStateUtil.matchesBusinessKeyAndSeqNum(stateKey, "B", 7));
    }

    @Test
    public void testMatchesBusinessKeyWithSeqNumFilter() throws Exception {
        Action action = new NoOpAction("match-action");
        InputEvent event = new InputEvent("match-input");
        String keyOneAtSeqFive = ActionStateUtil.generateKey(1L, 5, action, event, MAX_PARALLELISM);

        assertTrue(
                ActionStateUtil.matchesBusinessKeyWithSeqNum(keyOneAtSeqFive, 1L, seq -> seq <= 5));
        assertFalse(
                ActionStateUtil.matchesBusinessKeyWithSeqNum(keyOneAtSeqFive, 1L, seq -> seq > 5));
        // Wrong business key never matches, regardless of the seqNum filter.
        assertFalse(ActionStateUtil.matchesBusinessKeyWithSeqNum(keyOneAtSeqFive, 5L, seq -> true));
    }
}
