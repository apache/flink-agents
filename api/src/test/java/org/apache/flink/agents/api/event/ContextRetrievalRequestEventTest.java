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

package org.apache.flink.agents.api.event;

import org.apache.flink.agents.api.Event;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Tests for the max results validation of {@link ContextRetrievalRequestEvent}. */
class ContextRetrievalRequestEventTest {

    @Test
    @DisplayName("Zero max results is rejected with a message naming the parameter")
    void testZeroMaxResultsRejected() {
        assertThatThrownBy(() -> new ContextRetrievalRequestEvent("query", "store", 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max_results");
    }

    @Test
    @DisplayName(
            "Negative max results is rejected with a message naming the parameter and its value")
    void testNegativeMaxResultsRejected() {
        assertThatThrownBy(() -> new ContextRetrievalRequestEvent("query", "store", -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max_results")
                .hasMessageContaining("-1");
    }

    @Test
    @DisplayName("Positive max results is accepted")
    void testPositiveMaxResultsAccepted() {
        assertThat(new ContextRetrievalRequestEvent("query", "store", 5).getMaxResults())
                .isEqualTo(5);
    }

    @Test
    @DisplayName("Default max results is unchanged")
    void testDefaultMaxResultsUnchanged() {
        assertThat(new ContextRetrievalRequestEvent("query", "store").getMaxResults()).isEqualTo(3);
    }

    @Test
    @DisplayName("Reconstruction from attributes rejects non-positive max results")
    void testReconstructedNonPositiveMaxResultsRejected() {
        assertThatThrownBy(() -> new ContextRetrievalRequestEvent(UUID.randomUUID(), attributes(0)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max_results");
    }

    @Test
    @DisplayName("Reconstruction from a base event rejects non-positive max results")
    void testFromEventNonPositiveMaxResultsRejected() {
        assertThatThrownBy(() -> ContextRetrievalRequestEvent.fromEvent(baseEvent(-2)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max_results")
                .hasMessageContaining("-2");
    }

    @Test
    @DisplayName("Reconstruction of a valid event keeps max results")
    void testFromEventValidMaxResultsAccepted() {
        assertThat(ContextRetrievalRequestEvent.fromEvent(baseEvent(5)).getMaxResults())
                .isEqualTo(5);
    }

    @Test
    @DisplayName("Reconstruction tolerates a missing max results attribute")
    void testReconstructionWithoutMaxResultsAttribute() {
        Map<String, Object> attrs = attributes(5);
        attrs.remove("max_results");
        assertThatCode(() -> new ContextRetrievalRequestEvent(UUID.randomUUID(), attrs))
                .doesNotThrowAnyException();
    }

    private static Map<String, Object> attributes(int maxResults) {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("query", "query");
        attrs.put("vector_store", "store");
        attrs.put("max_results", maxResults);
        return attrs;
    }

    private static Event baseEvent(int maxResults) {
        return new Event(
                UUID.randomUUID(), ContextRetrievalRequestEvent.EVENT_TYPE, attributes(maxResults));
    }
}
