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

import java.util.HashMap;
import java.util.Map;

import static org.apache.flink.agents.runtime.actionstate.ActionStateUtil.generateKey;

public class InMemoryActionStateStore implements ActionStateStore {

    private final Map<String, ActionState> actionStates;

    public InMemoryActionStateStore() {
        this.actionStates = new HashMap<>();
    }

    @Override
    public void put(Object key, Action action, Event event, ActionState state) {
        actionStates.putIfAbsent(generateKey(key, action, event), state);
    }

    @Override
    public ActionState get(Object key, Action action, Event event) {
        return actionStates.get(generateKey(key, action, event));
    }

    @Override
    public void rebuildState(Object recoveryMarker) {
        // No-op for in-memory store as it does not persist state;
    }

    @Override
    public void cleanUpState() {
        actionStates.clear();
    }

    @VisibleForTesting
    public Map<String, ActionState> getActionStates() {
        return actionStates;
    }
}
