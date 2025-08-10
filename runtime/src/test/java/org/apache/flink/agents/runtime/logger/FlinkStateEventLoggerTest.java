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
import org.apache.flink.api.common.state.ListState;
import org.apache.flink.api.common.state.ListStateDescriptor;
import org.apache.flink.streaming.api.operators.StreamingRuntimeContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class FlinkStateEventLoggerTest {

    private FlinkStateEventLogger logger;
    @Mock private StreamingRuntimeContext mockRuntimeContext;
    @Mock private ListState<EventLogRecord> mockListState;

    @BeforeEach
    public void setUp() {
        MockitoAnnotations.openMocks(this);
        logger = new FlinkStateEventLogger();
    }

    @Test
    public void testOpenWithoutRuntimeContextThrowsException() {
        assertThrows(IllegalStateException.class, () -> logger.open());
    }

    @Test
    public void testOpenWithRuntimeContext() throws Exception {
        when(mockRuntimeContext.getListState(any(ListStateDescriptor.class)))
                .thenReturn(mockListState);

        logger.setStreamingRuntimeContext(mockRuntimeContext);
        logger.open();

        verify(mockRuntimeContext).getListState(any(ListStateDescriptor.class));
    }

    @Test
    public void testAppendWithoutOpenThrowsException() {
        EventContext context = new EventContext("test-key");
        Event event = new TestEvent();

        assertThrows(IllegalStateException.class, () -> logger.append(context, event));
    }

    @Test
    public void testAppendAfterOpen() throws Exception {
        when(mockRuntimeContext.getListState(any(ListStateDescriptor.class)))
                .thenReturn(mockListState);

        logger.setStreamingRuntimeContext(mockRuntimeContext);
        logger.open();

        EventContext context = new EventContext("test-key");
        Event event = new TestEvent();

        logger.append(context, event);

        verify(mockListState).add(any(EventLogRecord.class));
    }

    private static class TestEvent extends Event {}
}
