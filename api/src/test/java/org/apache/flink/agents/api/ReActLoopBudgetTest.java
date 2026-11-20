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

package org.apache.flink.agents.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.agents.api.agents.ReActLoopBudget;
import org.apache.flink.agents.api.agents.ReActLoopBudget.BudgetMode;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Tests for budget construction and defaults. */
class ReActLoopBudgetTest {
    @Test
    void defaultsAndExplicitNullsProduceUnlimitedSoftBudget() {
        var defaults = ReActLoopBudget.builder().build();
        var explicitNull =
                ReActLoopBudget.builder()
                        .maxTurns(null)
                        .maxTime(null)
                        .maxTokens(null)
                        .mode(null)
                        .build();
        assertThat(defaults.toMap()).isEqualTo(Map.of("mode", "SOFT"));
        assertThat(explicitNull.toMap()).isEqualTo(defaults.toMap());
    }

    @Test
    void builderProducesIndependentImmutableBudgets() throws Exception {
        var builder =
                ReActLoopBudget.builder()
                        .maxTurns(2)
                        .maxTime(Duration.ofSeconds(60))
                        .maxTokens(10000L)
                        .mode(BudgetMode.HARD);
        var first = builder.build();
        var second = builder.maxTurns(3).mode(null).build();
        assertThat(first.getMaxTurns()).isEqualTo(2);
        assertThat(first.getMode()).isEqualTo(BudgetMode.HARD);
        assertThat(second.getMaxTurns()).isEqualTo(3);
        assertThat(second.getMode()).isEqualTo(BudgetMode.SOFT);
        var mapper = new ObjectMapper();
        assertThat(
                        mapper.readValue(mapper.writeValueAsString(first), ReActLoopBudget.class)
                                .toMap())
                .isEqualTo(first.toMap());
    }

    @Test
    void buildValidatesLimits() {
        for (int value : new int[] {0, -1}) {
            assertThatThrownBy(() -> ReActLoopBudget.builder().maxTurns(value).build())
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> ReActLoopBudget.builder().maxTokens((long) value).build())
                    .isInstanceOf(IllegalArgumentException.class);
        }
        for (Duration time :
                new Duration[] {Duration.ZERO, Duration.ofMillis(-1), Duration.ofNanos(1)}) {
            assertThatThrownBy(() -> ReActLoopBudget.builder().maxTime(time).build())
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(
                        () ->
                                ReActLoopBudget.builder()
                                        .maxTime(Duration.ofSeconds(Long.MAX_VALUE))
                                        .build())
                .isInstanceOf(ArithmeticException.class);
    }

    @Test
    void nullSerializedModeUsesSoftDefault() throws Exception {
        var budget = new ObjectMapper().readValue("{\"mode\":null}", ReActLoopBudget.class);
        assertThat(budget.getMode()).isEqualTo(BudgetMode.SOFT);
        assertThat(budget.toMap()).isEqualTo(Map.of("mode", "SOFT"));
    }
}
