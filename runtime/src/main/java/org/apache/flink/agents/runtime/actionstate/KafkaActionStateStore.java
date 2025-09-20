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
import java.util.List;
import java.util.Map;

/**
 * An implementation of ActionStateStore that uses Kafka as the backend storage for action states.
 * This class provides methods to put, get, and retrieve all action states associated with a given
 * key and action.
 */
public class KafkaActionStateStore implements ActionStateStore {

    // In memory action state for quick state retrival, this map is only used during recovery
    private final Map<String, Map<String, ActionState>> keyedActionStates;

    @VisibleForTesting
    KafkaActionStateStore(Map<String, Map<String, ActionState>> keyedActionStates) {
        this.keyedActionStates = keyedActionStates;
    }

    /** Constructs a new KafkaActionStateStore with an empty in-memory action state map. */
    public KafkaActionStateStore() {
        this(new HashMap<>());
    }

    @Override
    public void put(Object key, long seqNum, Action action, Event event, ActionState state)
            throws IOException {
        // TODO: Implement me
    }

    @Override
    public ActionState get(Object key, long seqNum, Action action, Event event) throws IOException {
        // TODO: Implement me
        return null;
    }

    @Override
    public void rebuildState(List<Object> recoveryMarker) {
        // TODO: implement me
    }

    @Override
    public void pruneState(Object key, long seqNum) {
        // TODO: implement me
    }
}
