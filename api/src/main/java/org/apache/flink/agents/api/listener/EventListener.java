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
 * Interface for event listeners that are notified when events are processed.
 *
 * <p>EventListener provides a callback mechanism triggered after event processing completes. This
 * is useful for monitoring, metrics collection, debugging, or triggering side effects based on
 * event processing.
 *
 * <p>Event listeners are executed synchronously after the main event processing is complete but
 * before the next event is processed. Implementations should be lightweight and avoid blocking
 * operations to prevent impacting agent performance.
 */
public interface EventListener {
    /**
     * Called when an event has been processed.
     *
     * <p>This method is invoked after the event has been processed by the agent's actions. The
     * listener can inspect the event and its context to perform additional processing such as
     * logging, metrics collection, or triggering external notifications.
     *
     * <p><strong>Important:</strong> This method should not throw exceptions as they will be caught
     * and logged but will not affect the main event processing flow. Implementations should handle
     * their own error recovery.
     *
     * @param context The context associated with the event processing
     * @param event The event that was processed
     */
    void onEventProcessed(EventContext context, Event event);
}
