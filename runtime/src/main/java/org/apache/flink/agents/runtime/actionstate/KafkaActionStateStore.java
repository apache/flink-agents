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

/**
 * An implementation of ActionStateStore that uses Kafka as the backend storage for action states.
 * This class provides methods to put, get, and retrieve all action states associated with a given
 * key and action.
 *
 * <p>TODO: Implement the methods to interact with Kafka for storing and retrieving action states.
 */
public class KafkaActionStateStore implements ActionStateStore {

    @Override
    public void put(Object key, Action action, ActionState state) {}

    @Override
    public ActionState get(Object key, Action action) {
        return new ActionState(null);
    }

    @Override
    public Map<String, ActionState> getAll(Object key) {
        return Map.of();
    }
}
