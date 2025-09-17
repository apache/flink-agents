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
import org.apache.flink.agents.plan.actions.Action;
import org.apache.flink.annotation.VisibleForTesting;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.apache.flink.agents.runtime.actionstate.ActionStateUtil.generateKey;

/**
 * An in-memory implementation of {@link ActionStateStore} for testing and local execution purposes.
 * This implementation does not persist state across restarts.
 */
public class InMemoryActionStateStore implements ActionStateStore {

    private final Map<String, Map<String, ActionState>> keyedActionStates;
    private final boolean doCleanup;

    public InMemoryActionStateStore(boolean doCleanup) {
        this.keyedActionStates = new HashMap<>();
        this.doCleanup = doCleanup;
    }

    @Override
    public void put(Object key, long seqNum, Action action, Event event, ActionState state)
            throws IOException {
        Map<String, ActionState> actionStates =
                keyedActionStates.getOrDefault(key.toString(), new HashMap<>());
        actionStates.put(generateKey(key.toString(), seqNum, action, event), state);
        keyedActionStates.put(key.toString(), actionStates);
    }

    @Override
    public ActionState get(Object key, long seqNum, Action action, Event event) throws IOException {
        return keyedActionStates
                .getOrDefault(key.toString(), new HashMap<>())
                .get(generateKey(key.toString(), seqNum, action, event));
    }

    @Override
    public void rebuildState(List<Object> recoveryMarker) {
        // No-op for in-memory store as it does not persist state;
    }

    @Override
    public void pruneState(Object key, long seqNum) {
        if (doCleanup) {
            keyedActionStates.remove(key.toString());
        }
    }

    @VisibleForTesting
    public Map<String, Map<String, ActionState>> getKeyedActionStates() {
        return keyedActionStates;
    }

    @Override
    public void close() throws Exception {
        // do nothing
    }
}
