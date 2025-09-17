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
import org.apache.flink.agents.plan.Action;
import org.apache.flink.annotation.VisibleForTesting;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import static org.apache.flink.agents.runtime.actionstate.ActionStateUtil.generateKey;

/**
 * An implementation of ActionStateStore that uses Kafka as the backend storage for action states.
 * This class provides methods to put, get, and retrieve all action states associated with a given
 * key and action.
 */
public class KafkaActionStateStore implements ActionStateStore {

    // In memory action state for quick state retrival
    private final Map<String, ActionState> actionStates;

    // Track the max sequence number per key a key can have, anything greater that this should be
    // ignored
    private final Map<Object, Long> maxSeqNumPerKey = new HashMap<>();

    @VisibleForTesting
    KafkaActionStateStore(Map<String, ActionState> actionStates) {
        this.actionStates = actionStates;
    }

    /** Constructs a new KafkaActionStateStore with an empty in-memory action state map. */
    public KafkaActionStateStore() {
        this(new HashMap<>());
    }

    @Override
    public void put(Object key, long seqNum, Action action, Event event, ActionState state)
            throws IOException {
        actionStates.put(generateKey(key.toString() + seqNum, action, event), state);
        // TODO: Implement the logic to store the action state in Kafka
    }

    @Override
    public ActionState get(Object key, long seqNum, Action action, Event event) throws IOException {
        if (maxSeqNumPerKey.get(key) != null && seqNum > maxSeqNumPerKey.get(key)) {
            // If the seqNum is greater than the max seqNum that is not present in the map, we
            // should ignore it
            return null;
        }
        return actionStates.computeIfAbsent(
                generateKey(key.toString() + seqNum, action, event),
                k -> {
                    maxSeqNumPerKey.put(key, seqNum);
                    return null;
                });
    }

    @Override
    public void rebuildState(Object recoveryMarker) {
        // TODO: implement the logic to retrieve all action states associated with the key from
        //       Kafka
    }

    @Override
    public void cleanUpState() {
        // Only clean up in memory state. For kafka, we can't really delete messages from it
        actionStates.clear();
        maxSeqNumPerKey.clear();
    }
}
