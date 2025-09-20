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

import java.io.IOException;

/** Interface for storing and retrieving the state of actions performed by agents. */
public interface ActionStateStore {
    enum BackendType {
        INMEMORY("inmemory"),
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
     * @param action the action the agent is taking
     * @param event the event that triggered the action
     * @param state the current state of the whole task
     * @throws IOException when key generation failed
     */
    void put(Object key, Action action, Event event, ActionState state) throws IOException;

    /**
     * Retrieve the state of a specific action associated with a given key from the backend storage.
     *
     * @param key the key associated with the message
     * @param action the action the agent is taking
     * @param event the event that triggered the action
     * @return the state of the action, or null if not found
     * @throws IOException when key generation failed
     */
    ActionState get(Object key, Action action, Event event) throws IOException;

    /**
     * Retrieve all states associated with a given key from the backend storage and recover the
     * local action states.
     */
    void rebuildState(Object recoveryMarker);

    /** Clean up state store to avoid evergrowing storage usage. */
    void cleanUpState();

    /**
     * Get a marker object representing the current recovery point in the state store.
     *
     * @return a marker object, or null if not supported
     */
    default Object getRecoveryMarker() {
        return null;
    }

    /**
     * Set the recovery marker to restore the state store to a previous recovery point.
     *
     * @param recoveryMarker the recovery marker to restore from
     */
    default void setRecoveryMarker(Object recoveryMarker) {
        // Default implementation does nothing
    }
}
