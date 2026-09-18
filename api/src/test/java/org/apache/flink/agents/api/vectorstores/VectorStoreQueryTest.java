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

package org.apache.flink.agents.api.vectorstores;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Tests for {@link VectorStoreQuery}. */
class VectorStoreQueryTest {

    @Test
    @DisplayName("Null limit is rejected with a message naming the parameter")
    void testNullLimitRejected() {
        assertThatThrownBy(() -> new VectorStoreQuery("flink", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("limit");
    }

    @Test
    @DisplayName("Negative limit is rejected with a message naming the parameter and its value")
    void testNegativeLimitRejected() {
        assertThatThrownBy(() -> new VectorStoreQuery("flink", -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("limit")
                .hasMessageContaining("-1");
    }

    @Test
    @DisplayName("Negative limit is rejected through the fully specified constructor")
    void testNegativeLimitRejectedWithAllArguments() {
        assertThatThrownBy(
                        () ->
                                new VectorStoreQuery(
                                        VectorStoreQueryMode.SEMANTIC,
                                        "flink",
                                        -5,
                                        "collection",
                                        null,
                                        new HashMap<>()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("limit");
    }

    @Test
    @DisplayName("Zero and positive limits stay legal and are returned unchanged")
    void testLegalLimitsAccepted() {
        assertThat(new VectorStoreQuery("flink", 0).getLimit()).isZero();

        assertThat(new VectorStoreQuery("flink", 5).getLimit()).isEqualTo(5);

        assertThat(
                        new VectorStoreQuery(
                                        VectorStoreQueryMode.SEMANTIC,
                                        "flink",
                                        10,
                                        "collection",
                                        null,
                                        Collections.emptyMap())
                                .getLimit())
                .isEqualTo(10);
    }
}
