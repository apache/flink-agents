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

import java.io.IOException;
import java.util.List;

/** Interface for storing and retrieving the state of actions performed by agents. */
public interface ActionStateStore extends AutoCloseable {
    enum BackendType {
        KAFKA("kafka");

        private final String type;

        BackendType(String type) {
            this.type = type;
        }

        public String getType() {
            return type;
        }
    }

    /**
     * Store the state of a specific action associated with a given key to the backend storage.
     *
     * @param key the key associate with the message
     * @param seqNum the sequence number of the key
     * @param action the action the agent is taking
     * @param event the event that triggered the action
     * @param state the current state of the whole task
     * @throws IOException when key generation failed
     */
    void put(Object key, long seqNum, Action action, Event event, ActionState state)
            throws Exception;

    /**
     * Retrieve the state of a specific action associated with a given key from the backend storage.
     * It any of the sequence number for a key can't be found, all the states associated with the
     * key after the sequence number should be ignored and null will be returned.
     *
     * @param key the key associated with the message
     * @param seqNum the sequence number of the key
     * @param action the action the agent is taking
     * @param event the event that triggered the action
     * @return the state of the action, or null if not found
     * @throws IOException when key generation failed
     */
    ActionState get(Object key, long seqNum, Action action, Event event) throws Exception;

    /**
     * Rebuild the in-memory state from the backend storage using the provided recovery markers.
     *
     * @param recoveryMarkers a list of markers representing the recovery points
     */
    void rebuildState(List<Object> recoveryMarkers) throws Exception;

    /**
     * Prune the state for a given key.
     *
     * @param key the key whose state should be pruned
     * @param seqNum the sequence number up to which the state should be pruned
     */
    void pruneState(Object key, long seqNum);

    /**
     * Get a marker object representing the current recovery point in the state store.
     *
     * @return a marker object, or null if not supported
     */
    default Object getRecoveryMarker() {
        return null;
    }
}
