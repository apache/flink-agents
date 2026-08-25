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
import org.apache.flink.runtime.state.KeyGroupRangeAssignment;
import org.apache.flink.util.Preconditions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.function.IntPredicate;
import java.util.function.LongPredicate;

/** Utility class for action state related operations. */
public class ActionStateUtil {

    private static final Logger LOG = LoggerFactory.getLogger(ActionStateUtil.class);

    private static final JsonMapper MAPPER =
            JsonMapper.builder()
                    .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
                    .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
                    .build();
    private static final String KEY_SEPARATOR = "_";

    // Composite key layout: keyGroup_seqNum_eventUUID_actionUUID_businessKey.
    //
    // Every fixed field before the business key (key-group, seq-num, and the two UUIDs) is
    // guaranteed to be free of KEY_SEPARATOR, and the business key — the only caller-supplied,
    // variable-length field — is placed LAST. Parsing therefore splits with a fixed limit so the
    // final segment keeps the business key intact even when it contains the separator, e.g.
    // "tenant_user". No escaping is required and the segment count is always exact.
    private static final int KEY_GROUP_SEGMENT = 0;
    private static final int SEQ_NUM_SEGMENT = 1;
    private static final int EVENT_UUID_SEGMENT = 2;
    private static final int ACTION_UUID_SEGMENT = 3;
    private static final int BUSINESS_KEY_SEGMENT = 4;
    static final int KEY_SEGMENT_COUNT = 5;

    public static String generateKey(
            @Nonnull Object key,
            long seqNum,
            @Nonnull Action action,
            @Nonnull Event event,
            int maxParallelism)
            throws IOException {
        Preconditions.checkNotNull(key, "key cannot be null.");
        Preconditions.checkNotNull(action, "action cannot be null.");
        Preconditions.checkNotNull(event, "event cannot be null.");
        Preconditions.checkArgument(
                maxParallelism > 0,
                "maxParallelism must be positive but was %s; the store's maxParallelism must be"
                        + " set to the operator's max parallelism before writing action state.",
                maxParallelism);
        int keyGroup = KeyGroupRangeAssignment.assignToKeyGroup(key, maxParallelism);
        return String.join(
                KEY_SEPARATOR,
                String.valueOf(keyGroup),
                String.valueOf(seqNum),
                generateUUIDForEvent(event),
                generateUUIDForAction(action),
                key.toString());
    }

    /**
     * Parses a composite state key into its semantic fields, in the order {@code [keyGroup,
     * seqNum, eventUUID, actionUUID, businessKey]}. Throws when {@code key} is not in the current
     * format.
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
                parts[BUSINESS_KEY_SEGMENT]);
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
     * segment equals {@code businessKey}. The business key occupies its own trailing segment, so
     * the comparison is exact and cannot collide with another record's numeric segments.
     */
    public static boolean matchesBusinessKey(String stateKey, Object businessKey) {
        String[] parts = splitValidatedKey(stateKey);
        return parts != null && parts[BUSINESS_KEY_SEGMENT].equals(businessKey.toString());
    }

    /** Like {@link #matchesBusinessKey} with an additional exact sequence-number segment match. */
    public static boolean matchesBusinessKeyAndSeqNum(
            String stateKey, Object businessKey, long seqNum) {
        String[] parts = splitValidatedKey(stateKey);
        return parts != null
                && parts[BUSINESS_KEY_SEGMENT].equals(businessKey.toString())
                && parts[SEQ_NUM_SEGMENT].equals(String.valueOf(seqNum));
    }

    /**
     * Like {@link #matchesBusinessKey} with an additional predicate over the parsed sequence-number
     * segment. Returns {@code false} for keys that cannot be attributed (not the current format or
     * an unparsable sequence number): never prune what cannot be attributed.
     */
    public static boolean matchesBusinessKeyWithSeqNum(
            String stateKey, Object businessKey, LongPredicate seqNumFilter) {
        String[] parts = splitValidatedKey(stateKey);
        if (parts == null || !parts[BUSINESS_KEY_SEGMENT].equals(businessKey.toString())) {
            return false;
        }
        try {
            return seqNumFilter.test(Long.parseLong(parts[SEQ_NUM_SEGMENT]));
        } catch (NumberFormatException e) {
            LOG.warn("Failed to parse sequence number from state key: {}", stateKey);
            return false;
        }
    }

    /**
     * Returns {@code true} if the composite {@code stateKey}'s key-group is accepted by the given
     * ownership filter. A {@code null} filter retains every key (the default for in-memory and test
     * backends).
     *
     * <p>A key that does not have the expected segment count — or whose key-group segment cannot
     * be parsed — is dropped rather than retained: it cannot be attributed to a key-group, so
     * keeping it in every subtask would leak orphan state. This is safe because the project does
     * not preserve pre-format durable state.
     */
    public static boolean isKeyRetained(@Nullable IntPredicate ownershipFilter, String stateKey) {
        if (ownershipFilter == null) {
            return true;
        }
        String[] parts = splitValidatedKey(stateKey);
        if (parts == null) {
            LOG.warn(
                    "Dropping state key with unrecognized format during ownership filtering: {}",
                    stateKey);
            return false;
        }
        try {
            return ownershipFilter.test(Integer.parseInt(parts[KEY_GROUP_SEGMENT]));
        } catch (NumberFormatException e) {
            LOG.warn(
                    "Dropping state key with unparsable key-group during ownership filtering: {}",
                    stateKey,
                    e);
            return false;
        }
    }

    /**
     * Returns the business-key segment of {@code stateKey}, or {@code null} when {@code stateKey}
     * is not in the current format. The returned value preserves separators inside the business
     * key.
     */
    @Nullable
    public static String businessKeyOf(String stateKey) {
        Preconditions.checkNotNull(stateKey, "stateKey cannot be null.");
        String[] parts = splitValidatedKey(stateKey);
        return parts == null ? null : parts[BUSINESS_KEY_SEGMENT];
    }

    /**
     * Splits and validates a composite state key. Returns its {@link #KEY_SEGMENT_COUNT} segments
     * when {@code key} has the expected segment count, or {@code null} otherwise. The split is
     * bounded so the trailing business-key segment is returned intact even when it contains {@link
     * #KEY_SEPARATOR}.
     */
    @Nullable
    private static String[] splitValidatedKey(String key) {
        if (key == null) {
            return null;
        }
        String[] parts = key.split(KEY_SEPARATOR, KEY_SEGMENT_COUNT);
        if (parts.length != KEY_SEGMENT_COUNT) {
            return null;
        }
        return parts;
    }

    private static String generateUUIDForEvent(Event event) throws IOException {
        return String.valueOf(
                UUID.nameUUIDFromBytes(MAPPER.writeValueAsBytes(event.getAttributes())));
    }

    private static String generateUUIDForAction(Action action) throws IOException {
        return String.valueOf(
                UUID.nameUUIDFromBytes(
                        String.valueOf(action.hashCode()).getBytes(StandardCharsets.UTF_8)));
    }
}