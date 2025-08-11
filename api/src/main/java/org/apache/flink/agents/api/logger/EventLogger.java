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

/**
 * Interface for logging action events in the Flink Agents framework.
 *
 * <p>EventLogger provides a unified interface for capturing, filtering, and persisting events as
 * they flow through the agent execution pipeline. Implementations can target different storage
 * backends such as Flink state, external databases, or file systems.
 */
public interface EventLogger extends AutoCloseable {

    /**
     * Opens the event logger with the provided parameters.
     *
     * <p>This method is called before any events are logged. Implementations should initialize any
     * resources needed for logging, such as opening connections to external systems or preparing
     * buffers.
     *
     * @param params parameters for opening the event logger, including configuration and context
     * @throws Exception if the open operation fails
     */
    void open(EventLoggerOpenParams params) throws Exception;

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
     * Flush any buffered events to the underlying storage.
     *
     * <p>This method is called to ensure that all logged events are persisted. Implementations
     * should flush any in-memory buffers or caches to the target storage system.
     *
     * @throws Exception if flushing fails
     */
    void flush() throws Exception;

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
}
