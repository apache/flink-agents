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
package org.apache.flink.agents.runtime.operator;

import org.apache.flink.agents.api.agents.ShortTermMemoryTtlCleanupStrategy;
import org.apache.flink.api.common.state.StateTtlConfig;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that {@link OperatorStateManager#applyCleanupStrategy} maps each {@link
 * ShortTermMemoryTtlCleanupStrategy} value to the matching Flink {@link StateTtlConfig} cleanup
 * strategy, observed through {@code StateTtlConfig.getCleanupStrategies()}.
 *
 * <p>Limitation for {@code INCREMENTAL}: the resulting {@code StateTtlConfig} coincides with
 * Flink's default background cleanup (size 5, not-every-record), so the test cannot distinguish
 * "the incremental call fired" from "background cleanup defaulted on". It instead pins the chosen
 * literals (size 5, run-for-every-record false) and asserts {@code !inFullSnapshot()} to separate
 * it from {@code FULL_SNAPSHOT}. This documents the contract; a deliberate no-op for the {@code
 * INCREMENTAL} branch would not be caught, but every realistic wrong-method mutation is.
 */
class OperatorStateManagerTtlCleanupTest {

    private static StateTtlConfig.CleanupStrategies cleanupStrategiesFor(
            ShortTermMemoryTtlCleanupStrategy strategy) {
        return OperatorStateManager.applyCleanupStrategy(
                        StateTtlConfig.newBuilder(Duration.ofMillis(1)), strategy)
                .build()
                .getCleanupStrategies();
    }

    @Test
    void fullSnapshotEnablesFullSnapshotCleanup() {
        StateTtlConfig.CleanupStrategies strategies =
                cleanupStrategiesFor(ShortTermMemoryTtlCleanupStrategy.FULL_SNAPSHOT);

        assertThat(strategies.inFullSnapshot()).isTrue();
    }

    @Test
    void incrementalUsesPinnedLiterals() {
        StateTtlConfig.CleanupStrategies strategies =
                cleanupStrategiesFor(ShortTermMemoryTtlCleanupStrategy.INCREMENTAL);

        assertThat(strategies.inFullSnapshot()).isFalse();
        StateTtlConfig.IncrementalCleanupStrategy incremental =
                strategies.getIncrementalCleanupStrategy();
        assertThat(incremental).isNotNull();
        assertThat(incremental.getCleanupSize()).isEqualTo(5);
        assertThat(incremental.runCleanupForEveryRecord()).isFalse();
    }

    @Test
    void rocksdbCompactionFilterEnablesRocksdbCleanup() {
        StateTtlConfig.CleanupStrategies strategies =
                cleanupStrategiesFor(ShortTermMemoryTtlCleanupStrategy.ROCKSDB_COMPACTION_FILTER);

        assertThat(strategies.inRocksdbCompactFilter()).isTrue();
    }

    @Test
    void lazyDisablesBackgroundCleanup() {
        StateTtlConfig.CleanupStrategies strategies =
                cleanupStrategiesFor(ShortTermMemoryTtlCleanupStrategy.LAZY);

        assertThat(strategies.isCleanupInBackground()).isFalse();
    }
}
