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

import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.plan.actions.Action;
import org.apache.flink.annotation.Internal;
import org.apache.flink.api.common.typeutils.TypeSerializer;
import org.apache.flink.api.common.typeutils.TypeSerializerSnapshotSerializationUtil;
import org.apache.flink.core.memory.DataOutputSerializer;
import org.apache.flink.runtime.state.KeyGroupRangeAssignment;
import org.apache.flink.util.Preconditions;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.function.IntPredicate;
import java.util.function.LongPredicate;

/** Utility class for action state related operations. */
@Internal
public final class ActionStateUtil {

    private static final JsonMapper MAPPER =
            JsonMapper.builder()
                    .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
                    .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
                    .build();
    private static final String KEY_SEPARATOR = "_";
    private static final String KEY_FORMAT_VERSION = "v2";
    private static final String KEY_GROUP_PREFIX = KEY_FORMAT_VERSION + ":";

    // Composite key layout:
    // v2:keyGroup_seqNum_eventUUID_actionUUID_serializerFingerprint_businessKeyDigest.
    //
    // The final segment contains a SHA-256 digest of the bytes produced by Flink's key serializer.
    // This preserves the typed identity used by keyed state instead of collapsing distinct keys
    // through Object.toString(), while keeping durable keys bounded in size and avoiding embedding
    // serialized business-key data directly.
    private static final int KEY_GROUP_SEGMENT = 0;
    private static final int SEQ_NUM_SEGMENT = 1;
    private static final int EVENT_UUID_SEGMENT = 2;
    private static final int ACTION_UUID_SEGMENT = 3;
    private static final int SERIALIZER_FINGERPRINT_SEGMENT = 4;
    private static final int BUSINESS_KEY_IDENTITY_SEGMENT = 5;
    static final int KEY_SEGMENT_COUNT = 6;

    static <K> String generateKey(
            @Nonnull K key,
            long seqNum,
            @Nonnull Action action,
            @Nonnull Event event,
            int maxParallelism,
            @Nonnull TypeSerializer<K> keySerializer,
            String serializerFingerprint)
            throws IOException {
        Preconditions.checkNotNull(key, "key cannot be null.");
        Preconditions.checkNotNull(action, "action cannot be null.");
        Preconditions.checkNotNull(event, "event cannot be null.");
        Preconditions.checkNotNull(keySerializer, "keySerializer cannot be null.");
        Preconditions.checkNotNull(serializerFingerprint, "serializerFingerprint cannot be null.");
        Preconditions.checkArgument(seqNum >= 0, "seqNum must be nonnegative but was %s.", seqNum);
        Preconditions.checkArgument(
                maxParallelism > 0,
                "maxParallelism must be positive but was %s; the store's maxParallelism must be"
                        + " set to the operator's max parallelism before writing action state.",
                maxParallelism);
        int keyGroup = KeyGroupRangeAssignment.assignToKeyGroup(key, maxParallelism);
        return String.join(
                KEY_SEPARATOR,
                KEY_GROUP_PREFIX + keyGroup,
                String.valueOf(seqNum),
                generateUUIDForEvent(event),
                generateUUIDForAction(action),
                serializerFingerprint,
                generateBusinessKeyIdentity(key, keySerializer));
    }

    /** Returns a stable digest of a Flink key's serialized, type-preserving representation. */
    public static <K> String generateBusinessKeyIdentity(
            @Nonnull K key, @Nonnull TypeSerializer<K> keySerializer) {
        Preconditions.checkNotNull(key, "key cannot be null.");
        Preconditions.checkNotNull(keySerializer, "keySerializer cannot be null.");
        DataOutputSerializer output = new DataOutputSerializer(64);
        try {
            keySerializer.serialize(key, output);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Failed to serialize the Flink key for durable action state", e);
        }
        return sha256Base64(output.getCopyOfBuffer());
    }

    static String generateSerializerFingerprint(TypeSerializer<?> keySerializer) {
        Preconditions.checkNotNull(keySerializer, "keySerializer cannot be null.");
        DataOutputSerializer output = new DataOutputSerializer(128);
        try {
            TypeSerializerSnapshotSerializationUtil.writeSerializerSnapshot(
                    output, keySerializer.snapshotConfiguration());
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Failed to fingerprint the Flink key serializer for durable action state", e);
        }
        return sha256Base64(output.getCopyOfBuffer());
    }

    /**
     * Parses a composite state key into its semantic fields, in the order {@code [keyGroup, seqNum,
     * eventUUID, actionUUID, serializerFingerprint, businessKeyIdentity]}. Throws when {@code key}
     * is not in the current format.
     */
    public static List<String> parseKey(String key) {
        Preconditions.checkNotNull(key, "key cannot be null.");
        String[] parts = splitValidatedKey(key);
        Preconditions.checkArgument(parts != null, "Invalid key format.");
        return List.of(
                parts[KEY_GROUP_SEGMENT],
                parts[SEQ_NUM_SEGMENT],
                parts[EVENT_UUID_SEGMENT],
                parts[ACTION_UUID_SEGMENT],
                parts[SERIALIZER_FINGERPRINT_SEGMENT],
                parts[BUSINESS_KEY_IDENTITY_SEGMENT]);
    }

    /**
     * Extracts the key-group from a composite state key. The key-group was computed from the
     * original typed key via {@link KeyGroupRangeAssignment#assignToKeyGroup}. Throws when {@code
     * key} is not in the current format.
     */
    public static int parseKeyGroup(String key) {
        Preconditions.checkNotNull(key, "key cannot be null.");
        String[] parts = splitValidatedKey(key);
        Preconditions.checkArgument(parts != null, "Invalid key format.");
        return Integer.parseInt(parts[KEY_GROUP_SEGMENT]);
    }

    /**
     * Returns {@code true} when {@code stateKey} is in the current format and its business-key
     * identity segment equals {@code businessKeyIdentity}. The identity occupies its own trailing
     * segment, so the comparison is exact and cannot collide with another record's numeric
     * segments.
     */
    public static boolean matchesBusinessKeyIdentity(String stateKey, String businessKeyIdentity) {
        String[] parts = splitValidatedKey(stateKey);
        return parts != null && parts[BUSINESS_KEY_IDENTITY_SEGMENT].equals(businessKeyIdentity);
    }

    /** Like {@link #matchesBusinessKeyIdentity} with an exact sequence-number segment match. */
    public static boolean matchesBusinessKeyIdentityAndSeqNum(
            String stateKey, String businessKeyIdentity, long seqNum) {
        String[] parts = splitValidatedKey(stateKey);
        return parts != null
                && parts[BUSINESS_KEY_IDENTITY_SEGMENT].equals(businessKeyIdentity)
                && parts[SEQ_NUM_SEGMENT].equals(String.valueOf(seqNum));
    }

    /**
     * Like {@link #matchesBusinessKeyIdentity} with an additional predicate over the parsed
     * sequence-number segment. Returns {@code false} for keys that cannot be attributed (not the
     * current format or an unparsable sequence number): never prune what cannot be attributed.
     */
    public static boolean matchesBusinessKeyIdentityWithSeqNum(
            String stateKey, String businessKeyIdentity, LongPredicate seqNumFilter) {
        String[] parts = splitValidatedKey(stateKey);
        if (parts == null || !parts[BUSINESS_KEY_IDENTITY_SEGMENT].equals(businessKeyIdentity)) {
            return false;
        }
        try {
            return seqNumFilter.test(Long.parseLong(parts[SEQ_NUM_SEGMENT]));
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * Returns {@code true} if the composite {@code stateKey}'s key-group is accepted by the given
     * ownership filter. A {@code null} filter retains every valid key (the default for in-memory
     * and test backends).
     *
     * <p>Recovery fails for an unsupported format or invalid key field instead of silently reusing
     * or discarding durable state that cannot be attributed safely.
     */
    static boolean isKeyRetained(
            @Nullable IntPredicate ownershipFilter,
            String stateKey,
            int maxParallelism,
            String expectedSerializerFingerprint) {
        Preconditions.checkArgument(maxParallelism > 0, "maxParallelism must be positive.");
        Preconditions.checkNotNull(
                expectedSerializerFingerprint, "expectedSerializerFingerprint cannot be null.");
        String[] parts = splitValidatedKey(stateKey);
        if (parts == null) {
            if (stateKey != null && stateKey.startsWith(KEY_FORMAT_VERSION + ":")) {
                throw new IllegalStateException(
                        "Malformed v2 action-state key during recovery: " + stateKey);
            }
            throw new IllegalStateException(
                    "Unsupported action-state key format during recovery. The durable state was "
                            + "written by an incompatible version; use a new action-state topic or "
                            + "table when starting without an old checkpoint or savepoint. Key: "
                            + stateKey);
        }
        int keyGroup = parseCanonicalKeyGroup(parts[KEY_GROUP_SEGMENT], stateKey);
        if (keyGroup < 0 || keyGroup >= maxParallelism) {
            throw new IllegalStateException(
                    String.format(
                            "Action-state key-group %s is outside the configured range [0, %s). Key: %s",
                            keyGroup, maxParallelism, stateKey));
        }
        validateRecoveryFields(parts, expectedSerializerFingerprint, stateKey);
        return ownershipFilter == null || ownershipFilter.test(keyGroup);
    }

    /**
     * Returns the business-key identity segment of {@code stateKey}, or {@code null} when {@code
     * stateKey} is not in the current format.
     */
    @Nullable
    public static String businessKeyIdentityOf(String stateKey) {
        Preconditions.checkNotNull(stateKey, "stateKey cannot be null.");
        String[] parts = splitValidatedKey(stateKey);
        return parts == null ? null : parts[BUSINESS_KEY_IDENTITY_SEGMENT];
    }

    /**
     * Splits and validates a composite state key. Returns its {@link #KEY_SEGMENT_COUNT} segments
     * when {@code key} has the expected segment count, or {@code null} otherwise. The split is
     * bounded so the trailing business-key identity segment is returned intact.
     */
    @Nullable
    private static String[] splitValidatedKey(String key) {
        if (key == null) {
            return null;
        }
        String[] parts = key.split(KEY_SEPARATOR, KEY_SEGMENT_COUNT);
        if (parts.length != KEY_SEGMENT_COUNT
                || !parts[KEY_GROUP_SEGMENT].startsWith(KEY_GROUP_PREFIX)) {
            return null;
        }
        parts[KEY_GROUP_SEGMENT] = parts[KEY_GROUP_SEGMENT].substring(KEY_GROUP_PREFIX.length());
        return parts;
    }

    private static int parseCanonicalKeyGroup(String encodedKeyGroup, String stateKey) {
        try {
            int keyGroup = Integer.parseInt(encodedKeyGroup);
            if (!Integer.toString(keyGroup).equals(encodedKeyGroup)) {
                throw new NumberFormatException("noncanonical integer");
            }
            return keyGroup;
        } catch (NumberFormatException e) {
            throw new IllegalStateException(
                    "Invalid key-group '"
                            + encodedKeyGroup
                            + "' in action-state key during recovery: "
                            + stateKey,
                    e);
        }
    }

    private static void validateRecoveryFields(
            String[] parts, String expectedSerializerFingerprint, String stateKey) {
        validateSequenceNumber(parts[SEQ_NUM_SEGMENT], stateKey);
        validateUuid("event UUID", parts[EVENT_UUID_SEGMENT], stateKey);
        validateUuid("action UUID", parts[ACTION_UUID_SEGMENT], stateKey);
        validateDigest(
                parts[SERIALIZER_FINGERPRINT_SEGMENT],
                "serializer fingerprint",
                expectedSerializerFingerprint,
                stateKey);
        validateDigest(
                parts[BUSINESS_KEY_IDENTITY_SEGMENT], "business-key identity", null, stateKey);
    }

    private static void validateSequenceNumber(String encodedSequenceNumber, String stateKey) {
        try {
            long sequenceNumber = Long.parseLong(encodedSequenceNumber);
            if (sequenceNumber < 0
                    || !Long.toString(sequenceNumber).equals(encodedSequenceNumber)) {
                throw new NumberFormatException("negative or noncanonical long");
            }
        } catch (NumberFormatException e) {
            throw new IllegalStateException(
                    "Invalid sequence number '"
                            + encodedSequenceNumber
                            + "' in action-state key during recovery: "
                            + stateKey,
                    e);
        }
    }

    private static void validateUuid(String fieldName, String encodedUuid, String stateKey) {
        try {
            UUID uuid = UUID.fromString(encodedUuid);
            if (!uuid.toString().equals(encodedUuid)) {
                throw new IllegalArgumentException("noncanonical UUID");
            }
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "Invalid "
                            + fieldName
                            + " '"
                            + encodedUuid
                            + "' in action-state key during recovery: "
                            + stateKey,
                    e);
        }
    }

    private static void validateDigest(
            String encodedDigest,
            String fieldName,
            @Nullable String expectedDigest,
            String stateKey) {
        try {
            byte[] digest = Base64.getDecoder().decode(encodedDigest);
            if (digest.length != 32
                    || !Base64.getEncoder().encodeToString(digest).equals(encodedDigest)) {
                throw new IllegalArgumentException("not a canonical SHA-256 digest");
            }
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "Invalid "
                            + fieldName
                            + " '"
                            + encodedDigest
                            + "' in action-state key during recovery: "
                            + stateKey,
                    e);
        }
        if (expectedDigest != null && !expectedDigest.equals(encodedDigest)) {
            throw new IllegalStateException(
                    "Action-state key serializer fingerprint does not match the operator key serializer. Key: "
                            + stateKey);
        }
    }

    private static String generateUUIDForEvent(Event event) throws IOException {
        return String.valueOf(
                UUID.nameUUIDFromBytes(MAPPER.writeValueAsBytes(event.getAttributes())));
    }

    private static String generateUUIDForAction(Action action) throws IOException {
        // Action.hashCode() folds in JavaFunction's Class[] parameterTypes, and Class.hashCode()
        // is the per-JVM identity hash — so the hash-derived UUID changes on every process
        // restart and recovery lookups can never hit. Derive from the plan-unique action name,
        // which is stable across restarts.
        return String.valueOf(
                UUID.nameUUIDFromBytes(action.getName().getBytes(StandardCharsets.UTF_8)));
    }

    private static String sha256Base64(byte[] bytes) {
        try {
            return Base64.getEncoder()
                    .encodeToString(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private ActionStateUtil() {}
}
