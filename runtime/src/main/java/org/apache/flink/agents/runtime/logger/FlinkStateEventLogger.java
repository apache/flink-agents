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

package org.apache.flink.agents.runtime.logger;

import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.api.EventContext;
import org.apache.flink.agents.api.logger.EventLogRecord;
import org.apache.flink.agents.api.logger.EventLogger;
import org.apache.flink.api.common.state.ListState;
import org.apache.flink.api.common.state.ListStateDescriptor;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.streaming.api.operators.StreamingRuntimeContext;

/**
 * FlinkStateEventLogger is an implementation of EventLogger that uses Flink's state management
 * to log events.
 *
 * <p>This logger stores events in a ListState, allowing for efficient state management and
 * recovery in Flink streaming applications.
 */
public class FlinkStateEventLogger implements EventLogger {
    private StreamingRuntimeContext runtimeContext;
    private ListState<EventLogRecord> eventLogState;

    @Override
    public void open() throws Exception {
        if (runtimeContext == null) {
            throw new IllegalStateException(
                    "StreamingRuntimeContext must be set before calling open()");
        }
        eventLogState =
                runtimeContext.getListState(
                        new ListStateDescriptor<>(
                                "eventLogs", TypeInformation.of(EventLogRecord.class)));
    }

    @Override
    public void append(EventContext context, Event event) throws Exception {
        if (eventLogState == null) {
            throw new IllegalStateException("EventLogger is not initialized. Call open() first.");
        }
        eventLogState.add(new EventLogRecord(context, event));
    }

    @Override
    public void close() throws Exception {
        // The state will be automatically managed by Flink's state backend.
    }

    /**
     * Sets the StreamingRuntimeContext for this logger.
     *
     * <p>This method must be called before open() to ensure the logger is properly initialized.
     *
     * @param runtimeContext The StreamingRuntimeContext to set
     */
    public void setStreamingRuntimeContext(StreamingRuntimeContext runtimeContext) {
        this.runtimeContext = runtimeContext;
    }
}
