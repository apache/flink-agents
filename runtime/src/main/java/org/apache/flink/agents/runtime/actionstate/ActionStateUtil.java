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

    // Composite key layout: keyGroup_businessKey_seqNum_eventUUID_actionUUID.
    private static final int KEY_GROUP_SEGMENT = 0;
    private static final int BUSINESS_KEY_SEGMENT = 1;
    private static final int SEQ_NUM_SEGMENT = 2;
    private static final int KEY_SEGMENT_COUNT = 5;

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
                key.toString(),
                String.valueOf(seqNum),
                generateUUIDForEvent(event),
                generateUUIDForAction(action));
    }

    public static List<String> parseKey(String key) {
        Preconditions.checkNotNull(key, "key cannot be null.");
        String[] parts = key.split(KEY_SEPARATOR);
        Preconditions.checkArgument(parts.length == KEY_SEGMENT_COUNT, "Invalid key format.");
        return List.of(parts);
    }

    /**
     * Extracts the key-group from a composite state key. The key-group is the first segment and was
     * computed from the original typed key via {@link KeyGroupRangeAssignment#assignToKeyGroup}.
     * Rejects keys without the expected segment layout, including keys written in the pre-key-group
     * 4-segment format.
     */
    public static int parseKeyGroup(String key) {
        Preconditions.checkNotNull(key, "key cannot be null.");
        String[] parts = key.split(KEY_SEPARATOR);
        Preconditions.checkArgument(parts.length == KEY_SEGMENT_COUNT, "Invalid key format.");
        return Integer.parseInt(parts[KEY_GROUP_SEGMENT]);
    }

    /**
     * Returns {@code true} when {@code stateKey} has the expected segment layout and its
     * business-key segment equals {@code businessKey}. Comparison is segment-exact; substring
     * matching is deliberately avoided because a numeric business key can collide with another
     * record's sequence-number segment.
     */
    public static boolean matchesBusinessKey(String stateKey, Object businessKey) {
        String[] parts = stateKey.split(KEY_SEPARATOR);
        return parts.length == KEY_SEGMENT_COUNT
                && parts[BUSINESS_KEY_SEGMENT].equals(businessKey.toString());
    }

    /** Like {@link #matchesBusinessKey} with an additional exact sequence-number segment match. */
    public static boolean matchesBusinessKeyAndSeqNum(
            String stateKey, Object businessKey, long seqNum) {
        String[] parts = stateKey.split(KEY_SEPARATOR);
        return parts.length == KEY_SEGMENT_COUNT
                && parts[BUSINESS_KEY_SEGMENT].equals(businessKey.toString())
                && parts[SEQ_NUM_SEGMENT].equals(String.valueOf(seqNum));
    }

    /**
     * Like {@link #matchesBusinessKey} with an additional predicate over the parsed sequence-number
     * segment. Returns {@code false} for keys that cannot be attributed (malformed layout or
     * unparsable sequence number): never prune what cannot be attributed.
     */
    public static boolean matchesBusinessKeyWithSeqNum(
            String stateKey, Object businessKey, LongPredicate seqNumFilter) {
        String[] parts = stateKey.split(KEY_SEPARATOR);
        if (parts.length != KEY_SEGMENT_COUNT
                || !parts[BUSINESS_KEY_SEGMENT].equals(businessKey.toString())) {
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
     * <p>Keys without the expected segment layout — including records written in the pre-key-group
     * 4-segment format — are dropped deterministically: they cannot be attributed to a key-group,
     * and retaining them would resurrect the orphan-state leak while staying unreachable for
     * lookups, which always use the current 5-segment format. A 5-segment key whose key-group
     * segment fails to parse is retained as a fail-safe: prefer keeping a possibly-valid
     * current-format key over dropping it on a parse error.
     */
    public static boolean isKeyRetained(@Nullable IntPredicate ownershipFilter, String stateKey) {
        if (ownershipFilter == null) {
            return true;
        }
        String[] parts = stateKey.split(KEY_SEPARATOR);
        if (parts.length != KEY_SEGMENT_COUNT) {
            LOG.warn(
                    "Dropping action-state record whose key does not have the expected {}-segment"
                            + " layout (written by an older version?): {}",
                    KEY_SEGMENT_COUNT,
                    stateKey);
            return false;
        }
        try {
            return ownershipFilter.test(Integer.parseInt(parts[KEY_GROUP_SEGMENT]));
        } catch (NumberFormatException e) {
            LOG.warn(
                    "Failed to parse key-group from state key for ownership filtering; retaining"
                            + " as fail-safe: {}",
                    stateKey,
                    e);
            return true;
        }
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
