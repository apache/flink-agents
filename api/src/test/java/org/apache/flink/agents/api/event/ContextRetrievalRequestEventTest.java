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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
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
}
