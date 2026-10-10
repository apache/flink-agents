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

import org.apache.flink.agents.api.trace.TraceRecord;

/** Writes {@link TraceRecord} instances to an output backend. */
public interface TraceLogger extends AutoCloseable {

    /**
     * Opens the logger with the provided parameters.
     *
     * <p>This method is called before any records are logged. Implementations should initialize any
     * resources needed for logging, such as opening connections to external systems or preparing
     * buffers.
     *
     * @param params parameters for opening the logger, including configuration and context
     * @throws Exception if the open operation fails
     */
    void open(TraceLoggerOpenParams params) throws Exception;

    /**
     * Appends a selected record with its identity, relationships, and payload.
     *
     * <p>The caller selects records and supplies their detail. This method does not perform target
     * selection. OFF skips the record. STANDARD permits attribute truncation, and VERBOSE retains
     * full attributes; identity and relationship fields remain complete for written records.
     *
     * @param record the complete TraceRecord to write
     * @param detail the selected recording detail, never null
     * @throws Exception if the append operation fails
     */
    void append(TraceRecord record, TraceLogDetail detail) throws Exception;

    /**
     * Flush any buffered records to the underlying storage.
     *
     * <p>This method is called to ensure that all logged records are persisted. Implementations
     * should flush any in-memory buffers or caches to the target storage system.
     *
     * @throws Exception if flushing fails
     */
    void flush() throws Exception;

    /**
     * Close the logger and release resources.
     *
     * <p>This method is called during cleanup. Implementations should flush any remaining records
     * and release all resources.
     *
     * @throws Exception if closing fails
     */
    @Override
    void close() throws Exception;
}
