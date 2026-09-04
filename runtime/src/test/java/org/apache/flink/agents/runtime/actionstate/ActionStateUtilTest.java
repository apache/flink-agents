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
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import static org.apache.flink.agents.runtime.actionstate.ActionStateTestUtils.KEY_SERIALIZER;
import static org.apache.flink.agents.runtime.actionstate.ActionStateTestUtils.createKeyEncoder;
import static org.apache.flink.agents.runtime.actionstate.ActionStateTestUtils.createKeySerializer;
import static org.apache.flink.agents.runtime.actionstate.ActionStateTestUtils.generateKey;
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
        String key1 = generateKey(key, 1, action, inputEvent, MAX_PARALLELISM);
        String key2 = generateKey(key, 1, action, inputEvent2, MAX_PARALLELISM);

        // Keys should be the same for the same input
        assertEquals(key1, key2);
    }

    @Test
    public void testBusinessKeyIdentityIsStableAcrossSerializerInstances() {
        String first =
                ActionStateUtil.generateBusinessKeyIdentity(
                        new SameStringKey(7), createKeySerializer());
        String afterRecovery =
                ActionStateUtil.generateBusinessKeyIdentity(
                        new SameStringKey(7), createKeySerializer());

        assertEquals(first, afterRecovery);
        assertEquals(44, first.length());
    }

    @Test
    public void testBusinessKeyIdentityDoesNotDependOnPriorSerializedKeyTypes() {
        var firstSerializer = createKeySerializer();
        var secondSerializer = createKeySerializer();
        ActionStateUtil.generateBusinessKeyIdentity("priming-string", firstSerializer);
        ActionStateUtil.generateBusinessKeyIdentity(42L, secondSerializer);

        String first =
                ActionStateUtil.generateBusinessKeyIdentity(new SameStringKey(7), firstSerializer);
        String second =
                ActionStateUtil.generateBusinessKeyIdentity(new SameStringKey(7), secondSerializer);

        assertEquals(first, second);
    }

    @Test
    public void testGenerateKeyDifferentInputs() throws Exception {
        // Create test data
        Object key = "diff-test";
        Action action = new NoOpAction("diff-action");
        InputEvent inputEvent1 = new InputEvent("input1");
        InputEvent inputEvent2 = new InputEvent("input2");

        // Generate keys
        String key1 = generateKey(key, 1, action, inputEvent1, MAX_PARALLELISM);
        String key2 = generateKey(key, 1, action, inputEvent2, MAX_PARALLELISM);

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
                    generateKey(null, 1, action, inputEvent, MAX_PARALLELISM);
                });
    }

    @Test
    public void testGenerateKeyWithNullAction() {
        Object key = "test-key";
        InputEvent inputEvent = new InputEvent("test-input");

        assertThrows(
                NullPointerException.class,
                () -> {
                    generateKey(key, 1, null, inputEvent, MAX_PARALLELISM);
                });
    }

    @Test
    public void testGenerateKeyWithNullEvent() throws Exception {
        Object key = "test-key";
        Action action = new NoOpAction("test-action");

        assertThrows(
                NullPointerException.class,
                () -> {
                    generateKey(key, 1, action, null, MAX_PARALLELISM);
                });
    }

    @Test
    public void testGenerateKeyRejectsNonPositiveMaxParallelism() throws Exception {
        Object key = "test-key";
        Action action = new NoOpAction("test-action");
        InputEvent inputEvent = new InputEvent("test-input");

        assertThrows(
                IllegalArgumentException.class, () -> generateKey(key, 1, action, inputEvent, 0));
        assertThrows(
                IllegalArgumentException.class, () -> generateKey(key, 1, action, inputEvent, -1));
    }

    @Test
    public void testGenerateKeyRejectsNegativeSequenceNumber() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        generateKey(
                                "key",
                                -1,
                                new NoOpAction("action"),
                                new InputEvent("input"),
                                MAX_PARALLELISM));
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
                generateKey("test-key", 1, action, new InputEvent("test-input"), MAX_PARALLELISM);

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
                generateKey("test-key", 7, new NoOpAction("stable-name"), event, MAX_PARALLELISM);
        String second =
                generateKey("test-key", 7, new NoOpAction("stable-name"), event, MAX_PARALLELISM);
        assertEquals(first, second);
    }

    @Test
    public void testParseKeyValidKey() throws Exception {
        // Create test data and generate a key
        Object key = "test-key";
        Action action = new NoOpAction("test-action");
        InputEvent inputEvent = new InputEvent("test-input");
        long seqNum = 123;

        String generatedKey = generateKey(key, seqNum, action, inputEvent, MAX_PARALLELISM);

        // Parse the generated key
        List<String> parsedParts = ActionStateUtil.parseKey(generatedKey);

        // Verify: [keyGroup, seqNum, eventUUID, actionUUID, serializer, businessKeyIdentity].
        assertEquals(6, parsedParts.size());
        assertTrue(Integer.parseInt(parsedParts.get(0)) >= 0); // keyGroup
        assertEquals(String.valueOf(seqNum), parsedParts.get(1));
        // The event and action UUID segments are non-empty.
        assertTrue(parsedParts.get(2).length() > 0);
        assertTrue(parsedParts.get(3).length() > 0);
        assertEquals(
                ActionStateUtil.generateBusinessKeyIdentity(key, KEY_SERIALIZER),
                parsedParts.get(5));
    }

    @Test
    public void testParseKeyRoundTrip() throws Exception {
        // Test that generate -> parse -> values match original inputs
        Object originalKey = "round-trip-test";
        Action action = new NoOpAction("round-trip-action");
        InputEvent inputEvent = new InputEvent("round-trip-input");
        long seqNum = 456;

        String generatedKey = generateKey(originalKey, seqNum, action, inputEvent, MAX_PARALLELISM);
        List<String> parsedParts = ActionStateUtil.parseKey(generatedKey);

        assertEquals(
                ActionStateUtil.generateBusinessKeyIdentity(originalKey, KEY_SERIALIZER),
                parsedParts.get(5));
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

        String generatedKey = generateKey(key, seqNum, action, inputEvent, MAX_PARALLELISM);
        List<String> parsedParts = ActionStateUtil.parseKey(generatedKey);

        assertEquals(
                ActionStateUtil.generateBusinessKeyIdentity(key, KEY_SERIALIZER),
                parsedParts.get(5));
        assertEquals(String.valueOf(seqNum), parsedParts.get(1));
    }

    @Test
    public void testParseKeyConsistencyWithDifferentKeys() throws Exception {
        // Generate keys with different inputs and verify parsing consistency
        Action action = new NoOpAction("consistency-action");
        InputEvent inputEvent = new InputEvent("consistency-input");

        String key1 = generateKey("key1", 100, action, inputEvent, MAX_PARALLELISM);
        String key2 = generateKey("key2", 200, action, inputEvent, MAX_PARALLELISM);

        List<String> parsed1 = ActionStateUtil.parseKey(key1);
        List<String> parsed2 = ActionStateUtil.parseKey(key2);

        // Business keys and sequence numbers differ.
        assertEquals(parsed1.get(4), parsed2.get(4)); // serializer fingerprint
        assertNotEquals(parsed1.get(5), parsed2.get(5)); // businessKey
        assertNotEquals(parsed1.get(1), parsed2.get(1)); // seqNum

        // But event and action UUIDs should be the same (same event and action)
        assertEquals(parsed1.get(2), parsed2.get(2)); // Event UUID
        assertEquals(parsed1.get(3), parsed2.get(3)); // Action UUID
    }

    @Test
    public void testIsKeyRetainedFiltersForeignKeys() throws Exception {
        Action action = new NoOpAction("owner-action");
        InputEvent event = new InputEvent("owner-input");
        String ownedKey = generateKey("A", 1, action, event, MAX_PARALLELISM);
        String foreignKey = generateKey("B", 1, action, event, MAX_PARALLELISM);

        int ownedKeyGroup = ActionStateUtil.parseKeyGroup(ownedKey);
        assertTrue(
                createKeyEncoder(MAX_PARALLELISM)
                        .isKeyRetained(kg -> kg == ownedKeyGroup, ownedKey));
        assertFalse(
                createKeyEncoder(MAX_PARALLELISM)
                        .isKeyRetained(kg -> kg == ownedKeyGroup, foreignKey));
    }

    @Test
    public void testIsKeyRetainedKeepsAllKeysWhenNoFilter() throws Exception {
        Action action = new NoOpAction("no-filter-action");
        InputEvent event = new InputEvent("no-filter-input");
        String keyA = generateKey("A", 1, action, event, MAX_PARALLELISM);
        String keyB = generateKey("B", 1, action, event, MAX_PARALLELISM);

        assertTrue(createKeyEncoder(MAX_PARALLELISM).isKeyRetained(null, keyA));
        assertTrue(createKeyEncoder(MAX_PARALLELISM).isKeyRetained(null, keyB));
    }

    @Test
    public void testIsKeyRetainedRejectsUnrecognizedFormatKeys() {
        assertThrows(
                IllegalStateException.class,
                () ->
                        createKeyEncoder(MAX_PARALLELISM)
                                .isKeyRetained(
                                        kg -> true, "12_1_event-uuid_action-uuid_business-key"));
        assertThrows(
                IllegalStateException.class,
                () -> createKeyEncoder(MAX_PARALLELISM).isKeyRetained(kg -> true, "malformed-key"));
    }

    @Test
    public void testIsKeyRetainedRejectsKeyWithUnparsableKeyGroup() throws Exception {
        String valid =
                generateKey(
                        "A",
                        1,
                        new NoOpAction("valid-action"),
                        new InputEvent("valid-input"),
                        MAX_PARALLELISM);
        String invalid = "v2:not-a-number" + valid.substring(valid.indexOf('_'));
        IllegalStateException failure =
                assertThrows(
                        IllegalStateException.class,
                        () -> createKeyEncoder(MAX_PARALLELISM).isKeyRetained(kg -> true, invalid));

        assertTrue(failure.getMessage().contains("Invalid key-group"));
        assertThrows(
                IllegalStateException.class,
                () -> createKeyEncoder(MAX_PARALLELISM).isKeyRetained(null, invalid));
    }

    @Test
    public void testIsKeyRetainedRejectsMalformedCurrentFormatFields() throws Exception {
        String valid =
                generateKey(
                        "A",
                        1,
                        new NoOpAction("valid-action"),
                        new InputEvent("valid-input"),
                        MAX_PARALLELISM);
        List<String> parts = ActionStateUtil.parseKey(valid);
        String nonCanonicalKeyGroup = withSegment(parts, 0, "+0");
        IllegalStateException nonCanonicalFailure =
                assertThrows(
                        IllegalStateException.class,
                        () ->
                                createKeyEncoder(MAX_PARALLELISM)
                                        .isKeyRetained(null, nonCanonicalKeyGroup));
        assertTrue(nonCanonicalFailure.getMessage().contains("+0"));
        assertTrue(nonCanonicalFailure.getMessage().contains(nonCanonicalKeyGroup));

        List<String> invalidKeys =
                List.of(
                        withSegment(parts, 0, "-1"),
                        withSegment(parts, 0, String.valueOf(MAX_PARALLELISM)),
                        withSegment(parts, 0, "00"),
                        withSegment(parts, 1, "not-a-number"),
                        withSegment(parts, 1, "+1"),
                        withSegment(parts, 1, "01"),
                        withSegment(parts, 1, "-0"),
                        withSegment(parts, 1, "-1"),
                        withSegment(parts, 2, "not-a-uuid"),
                        withSegment(parts, 2, "1-1-1-1-1"),
                        withSegment(parts, 3, "1-1-1-1-1"),
                        withSegment(parts, 4, "not-a-digest"),
                        withSegment(parts, 5, "not-a-digest"));

        for (String invalidKey : invalidKeys) {
            assertThrows(
                    IllegalStateException.class,
                    () -> createKeyEncoder(MAX_PARALLELISM).isKeyRetained(null, invalidKey),
                    invalidKey);
        }
    }

    @Test
    public void testBusinessKeyContainingSeparatorIsHandled() throws Exception {
        Object businessKey = "tenant_user";
        Action action = new NoOpAction("underscore-action");
        InputEvent event = new InputEvent("underscore-input");
        String stateKey = generateKey(businessKey, 3, action, event, MAX_PARALLELISM);
        String businessKeyIdentity =
                ActionStateUtil.generateBusinessKeyIdentity(businessKey, KEY_SERIALIZER);

        assertEquals(businessKeyIdentity, ActionStateUtil.businessKeyIdentityOf(stateKey));
        assertEquals(businessKeyIdentity, ActionStateUtil.parseKey(stateKey).get(5));
        assertTrue(ActionStateUtil.matchesBusinessKeyIdentity(stateKey, businessKeyIdentity));
        assertTrue(
                ActionStateUtil.matchesBusinessKeyIdentityAndSeqNum(
                        stateKey, businessKeyIdentity, 3));

        int ownedKeyGroup = ActionStateUtil.parseKeyGroup(stateKey);
        assertTrue(
                createKeyEncoder(MAX_PARALLELISM)
                        .isKeyRetained(kg -> kg == ownedKeyGroup, stateKey));
        assertFalse(
                createKeyEncoder(MAX_PARALLELISM)
                        .isKeyRetained(kg -> kg != ownedKeyGroup, stateKey));
    }

    @Test
    public void testMatchesBusinessKeyIsSegmentExact() throws Exception {
        Action action = new NoOpAction("match-action");
        InputEvent event = new InputEvent("match-input");
        // Numeric business key 1 at seqNum 5: a substring match on "_5_" would wrongly
        // attribute this record to business key 5 via its seqNum segment.
        String keyOneAtSeqFive = generateKey(1L, 5, action, event, MAX_PARALLELISM);
        String keyOneIdentity = ActionStateUtil.generateBusinessKeyIdentity(1L, KEY_SERIALIZER);
        String keyFiveIdentity = ActionStateUtil.generateBusinessKeyIdentity(5L, KEY_SERIALIZER);

        assertTrue(ActionStateUtil.matchesBusinessKeyIdentity(keyOneAtSeqFive, keyOneIdentity));
        assertFalse(ActionStateUtil.matchesBusinessKeyIdentity(keyOneAtSeqFive, keyFiveIdentity));
        assertFalse(
                ActionStateUtil.matchesBusinessKeyIdentity(
                        "legacy_1_event-uuid_action-uuid", keyOneIdentity));
    }

    @Test
    public void testMatchesBusinessKeyAndSeqNum() throws Exception {
        Action action = new NoOpAction("match-action");
        InputEvent event = new InputEvent("match-input");
        String stateKey = generateKey("A", 7, action, event, MAX_PARALLELISM);
        String identityA = ActionStateUtil.generateBusinessKeyIdentity("A", KEY_SERIALIZER);
        String identityB = ActionStateUtil.generateBusinessKeyIdentity("B", KEY_SERIALIZER);

        assertTrue(ActionStateUtil.matchesBusinessKeyIdentityAndSeqNum(stateKey, identityA, 7));
        assertFalse(ActionStateUtil.matchesBusinessKeyIdentityAndSeqNum(stateKey, identityA, 8));
        assertFalse(ActionStateUtil.matchesBusinessKeyIdentityAndSeqNum(stateKey, identityB, 7));
    }

    @Test
    public void testMatchesBusinessKeyWithSeqNumFilter() throws Exception {
        Action action = new NoOpAction("match-action");
        InputEvent event = new InputEvent("match-input");
        String keyOneAtSeqFive = generateKey(1L, 5, action, event, MAX_PARALLELISM);
        String keyOneIdentity = ActionStateUtil.generateBusinessKeyIdentity(1L, KEY_SERIALIZER);
        String keyFiveIdentity = ActionStateUtil.generateBusinessKeyIdentity(5L, KEY_SERIALIZER);

        assertTrue(
                ActionStateUtil.matchesBusinessKeyIdentityWithSeqNum(
                        keyOneAtSeqFive, keyOneIdentity, seq -> seq <= 5));
        assertFalse(
                ActionStateUtil.matchesBusinessKeyIdentityWithSeqNum(
                        keyOneAtSeqFive, keyOneIdentity, seq -> seq > 5));
        // Wrong business key never matches, regardless of the seqNum filter.
        assertFalse(
                ActionStateUtil.matchesBusinessKeyIdentityWithSeqNum(
                        keyOneAtSeqFive, keyFiveIdentity, seq -> true));
    }

    @Test
    public void testTypedKeysWithSameStringFormHaveDistinctIdentities() throws Exception {
        Action action = new NoOpAction("typed-key-action");
        InputEvent event = new InputEvent("typed-key-input");

        String numericKey = generateKey(1L, 1, action, event, 1);
        String stringKey = generateKey("1", 1, action, event, 1);

        assertNotEquals(numericKey, stringKey);
        assertFalse(
                ActionStateUtil.matchesBusinessKeyIdentity(
                        stringKey,
                        ActionStateUtil.generateBusinessKeyIdentity(1L, KEY_SERIALIZER)));
    }

    @Test
    public void testDistinctCustomKeysWithSameStringFormHaveDistinctIdentities() throws Exception {
        Action action = new NoOpAction("custom-key-action");
        InputEvent event = new InputEvent("custom-key-input");

        String first = generateKey(new SameStringKey(1), 1, action, event, 1);
        String second = generateKey(new SameStringKey(2), 1, action, event, 1);
        String equalToFirst = generateKey(new SameStringKey(1), 1, action, event, 1);

        assertNotEquals(first, second);
        assertEquals(first, equalToFirst);
    }

    private static final class SameStringKey {
        private final int id;

        private SameStringKey(int id) {
            this.id = id;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof SameStringKey && id == ((SameStringKey) other).id;
        }

        @Override
        public int hashCode() {
            return Objects.hash(id);
        }

        @Override
        public String toString() {
            return "same";
        }
    }

    private static String withSegment(List<String> parsedParts, int index, String replacement) {
        List<String> parts = new ArrayList<>(parsedParts);
        parts.set(index, replacement);
        parts.set(0, "v2:" + parts.get(0));
        return String.join("_", parts);
    }
}
