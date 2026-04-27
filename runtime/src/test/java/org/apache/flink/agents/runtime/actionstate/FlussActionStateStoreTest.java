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

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.api.InputEvent;
import org.apache.flink.agents.api.OutputEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link FlussActionStateStore} serialization and in-memory behavior. These tests
 * verify the ObjectMapper configuration and ActionState serialization round-trips without requiring
 * a real Fluss cluster.
 */
public class FlussActionStateStoreTest {

    private ObjectMapper objectMapper;

    @JsonTypeInfo(
            use = JsonTypeInfo.Id.CLASS,
            include = JsonTypeInfo.As.PROPERTY,
            property = "@class")
    abstract static class EventTypeInfoMixin {}

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.addMixIn(Event.class, EventTypeInfoMixin.class);
        objectMapper.addMixIn(InputEvent.class, EventTypeInfoMixin.class);
        objectMapper.addMixIn(OutputEvent.class, EventTypeInfoMixin.class);
    }

    @Test
    void testCompletionOnlyFlow() throws Exception {
        InputEvent taskEvent = new InputEvent("test-data");
        ActionState state = new ActionState(taskEvent);
        CallResult successResult =
                new CallResult(
                        "myFunction",
                        "argsDigest123",
                        "result-payload".getBytes(StandardCharsets.UTF_8));
        state.addCallResult(successResult);

        byte[] payload = objectMapper.writeValueAsBytes(state);
        ActionState retrieved = objectMapper.readValue(payload, ActionState.class);

        assertThat(retrieved).isNotNull();
        assertThat(retrieved.getCallResults()).hasSize(1);
        CallResult retrievedResult = retrieved.getCallResult(0);
        assertThat(retrievedResult.getFunctionId()).isEqualTo("myFunction");
        assertThat(retrievedResult.getArgsDigest()).isEqualTo("argsDigest123");
        assertThat(retrievedResult.isSuccess()).isTrue();
        assertThat(retrievedResult.getResultPayload())
                .isEqualTo("result-payload".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void testMultipleCallResults() throws Exception {
        InputEvent taskEvent = new InputEvent("test-data");
        ActionState state = new ActionState(taskEvent);
        state.addCallResult(
                new CallResult("fn1", "d1", "ok".getBytes(StandardCharsets.UTF_8))); // SUCCEEDED
        state.addCallResult(CallResult.pending("fn2", "d2")); // PENDING
        state.addCallResult(
                new CallResult(
                        "fn3", "d3", null, "error".getBytes(StandardCharsets.UTF_8))); // FAILED

        byte[] payload = objectMapper.writeValueAsBytes(state);
        ActionState retrieved = objectMapper.readValue(payload, ActionState.class);

        assertThat(retrieved).isNotNull();
        assertThat(retrieved.getCallResults()).hasSize(3);
        assertThat(retrieved.getCallResult(0).isSuccess()).isTrue();
        assertThat(retrieved.getCallResult(1).isPending()).isTrue();
        assertThat(retrieved.getCallResult(2).isFailure()).isTrue();
    }

    @Test
    void testCompletedAction() throws Exception {
        InputEvent taskEvent = new InputEvent("test-data");
        ActionState state = new ActionState(taskEvent);
        state.addCallResult(new CallResult("fn1", "d1", "result".getBytes(StandardCharsets.UTF_8)));
        state.markCompleted();

        byte[] payload = objectMapper.writeValueAsBytes(state);
        ActionState retrieved = objectMapper.readValue(payload, ActionState.class);

        assertThat(retrieved).isNotNull();
        assertThat(retrieved.isCompleted()).isTrue();
        assertThat(retrieved.getCallResults()).isEmpty();
    }

    @Test
    void testFullSerializationRoundTrip() throws Exception {
        InputEvent taskEvent = new InputEvent("full-test-data");
        ActionState state = new ActionState(taskEvent);
        state.addEvent(new InputEvent("output-event-1"));
        state.addEvent(new InputEvent("output-event-2"));
        state.addCallResult(
                new CallResult("fn1", "digest1", "payload1".getBytes(StandardCharsets.UTF_8)));
        state.addCallResult(CallResult.pending("fn2", "digest2"));

        byte[] payload = objectMapper.writeValueAsBytes(state);
        ActionState retrieved = objectMapper.readValue(payload, ActionState.class);

        assertThat(retrieved).isNotNull();
        assertThat(retrieved.getTaskEvent()).isEqualTo(taskEvent);
        assertThat(retrieved.getOutputEvents()).hasSize(2);
        assertThat(retrieved.getCallResults()).hasSize(2);
        assertThat(retrieved.getCallResult(0).isSuccess()).isTrue();
        assertThat(retrieved.getCallResult(1).isPending()).isTrue();
        assertThat(retrieved.isCompleted()).isFalse();
    }
}
