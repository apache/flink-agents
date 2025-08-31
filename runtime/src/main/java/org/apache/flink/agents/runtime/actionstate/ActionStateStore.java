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

import org.apache.flink.agents.plan.Action;

import java.util.Map;

/** Interface for storing and retrieving the state of actions performed by agents. */
public interface ActionStateStore {
    /**
     * Store the state of a specific action associated with a given key to the backend storage.
     *
     * @param key the key associate with the message
     * @param action the action the agent is taking
     * @param state the current state of the whole task
     */
    public void put(Object key, Action action, ActionState state);

    /**
     * Retrieve the state of a specific action associated with a given key from the backend storage.
     *
     * @param key the key associated with the message
     * @param action the action the agent is taking
     * @return the state of the action, or null if not found
     */
    public ActionState get(Object key, Action action);

    /**
     * Retrieve all states associated with a given key from the backend storage.
     *
     * @param key the key associated with the message
     * @return a map of key of action to action states associated with the key
     */
    public Map<String, ActionState> getAll(Object key);
}
