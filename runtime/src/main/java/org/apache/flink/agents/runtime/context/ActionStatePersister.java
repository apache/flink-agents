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

package org.apache.flink.agents.runtime.context;

import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.plan.actions.Action;
import org.apache.flink.agents.runtime.actionstate.ActionState;

/**
 * Interface for persisting {@link ActionState}.
 *
 * <p>This interface decouples the {@link RunnerContextImpl.DurableExecutionContext} from the
 * storage layer.
 */
public interface ActionStatePersister {

    /**
     * Persists the given ActionState.
     *
     * @param key the key for the action
     * @param sequenceNumber the sequence number for ordering
     * @param action the action being executed
     * @param event the event that triggered the action
     * @param actionState the ActionState to persist
     */
    void persist(
            Object key, long sequenceNumber, Action action, Event event, ActionState actionState);
}
