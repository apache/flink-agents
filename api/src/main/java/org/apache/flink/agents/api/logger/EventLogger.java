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

package org.apache.flink.agents.api.logger;

import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.api.EventContext;
import org.apache.flink.agents.api.EventFilter;

/**
 * Interface for logging action events in the Flink Agents framework.
 *
 * <p>EventLogger provides a unified interface for capturing, filtering, and persisting events as
 * they flow through the agent execution pipeline. Implementations can target different storage
 * backends such as Flink state, external databases, or file systems.
 */
public interface EventLogger extends AutoCloseable {

    /**
     * Initialize the event logger.
     *
     * <p>This method is called once during setup to prepare the logging infrastructure.
     * Implementations should establish connections, initialize state, and prepare for logging
     * operations.
     *
     * @throws Exception if initialization fails
     */
    void open() throws Exception;

    /**
     * Appends an event along with its associated context to the logger.
     *
     * <p>This method is invoked for each event that passes the configured filter. Implementations
     * should perform the append operation efficiently, as it may be called frequently during event
     * processing.
     *
     * @param context metadata and other contextual information associated with the event
     * @param event the event to be logged
     * @throws Exception if the append operation fails
     */
    void append(EventContext context, Event event) throws Exception;

    /**
     * Close the event logger and release resources.
     *
     * <p>This method is called during cleanup. Implementations should flush any remaining records
     * and release all resources.
     *
     * @throws Exception if closing fails
     */
    @Override
    void close() throws Exception;

    /**
     * Unwraps the EventLogger if it is wrapped by another logger or filter.
     *
     * <p>This method can be overridden by implementations that wrap other loggers or filters to
     * return the underlying logger.
     *
     * @return the underlying EventLogger instance
     */
    default EventLogger unwrap() {
        return this;
    }

    /**
     * Creates a new EventLogger that filters events based on the provided EventFilter.
     *
     * <p>This method allows wrapping an existing EventLogger with a filter to control which events
     * are logged. Only events accepted by the filter will be passed to the delegate logger.
     *
     * @param delegate the original EventLogger to wrap
     * @param filter the EventFilter to apply
     * @return a new EventLogger that applies the filter
     */
    static EventLogger withFilter(EventLogger delegate, EventFilter filter) {
        return new EventLogger() {
            @Override
            public void open() throws Exception {
                delegate.open();
            }

            @Override
            public void append(EventContext context, Event event) throws Exception {
                if (filter.accept(event, context)) {
                    delegate.append(context, event);
                }
            }

            @Override
            public void close() throws Exception {
                delegate.close();
            }

            @Override
            public EventLogger unwrap() {
                return delegate;
            }
        };
    }
}
