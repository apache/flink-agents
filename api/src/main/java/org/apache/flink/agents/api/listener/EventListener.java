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

package org.apache.flink.agents.api.listener;

import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.api.EventContext;

/**
 * Interface for event listeners that are notified when events are received for processing.
 *
 * <p>EventListener provides a callback mechanism triggered at the beginning of event processing.
 * This is useful for monitoring, metrics collection, debugging, or triggering side effects based on
 * event reception.
 *
 * <p>Event listeners are executed synchronously when an event is received, before any actions are
 * triggered. Implementations should be lightweight and avoid blocking operations to prevent
 * impacting agent performance.
 *
 * <p><strong>Note:</strong> Implementing classes must provide a public no-argument constructor to
 * allow for dynamic instantiation by the agent.
 */
public interface EventListener {
    /**
     * Called when an event is being processed.
     *
     * <p>This method is invoked when an event is received by the agent, before it is processed by
     * any actions. The listener can inspect the event and its context to perform additional
     * processing such as logging, metrics collection, or triggering external notifications.
     *
     * <p><strong>Important:</strong> This method should not throw exceptions as they will be caught
     * and logged but will not affect the main event processing flow. Implementations should handle
     * their own error recovery.
     *
     * @param context The context associated with the event
     * @param event The event that is being processed
     */
    void onEventProcessed(EventContext context, Event event);
}
