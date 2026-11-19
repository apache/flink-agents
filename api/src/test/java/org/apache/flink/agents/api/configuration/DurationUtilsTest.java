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

package org.apache.flink.agents.api.configuration;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.agents.api.agents.AgentExecutionOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Parser and precision coverage. Text cases shared with the Python duration tests are {@code
 * duration-cases.json}.
 */
class DurationUtilsTest {

    @ParameterizedTest
    @MethodSource("sharedCases")
    void sharedFixtureParsesOrRejects(SharedCase row) {
        if (row.rejection != null) {
            assertThatThrownBy(() -> DurationUtils.parse(row.input))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(row.rejection);
            return;
        }
        assertThat(row.microseconds).isNotNull();
        assertThat(row.canonical).isNotNull();
        Duration parsed = DurationUtils.parse(row.input);
        assertThat(parsed).isEqualTo(Duration.ofNanos(row.microseconds * 1_000L));
        assertThat(DurationUtils.format(parsed)).isEqualTo(row.canonical);
    }

    static List<SharedCase> sharedCases() throws IOException {
        try (InputStream input =
                DurationUtilsTest.class.getResourceAsStream("/duration-cases.json")) {
            assertThat(input).as("duration-cases.json").isNotNull();
            return new ObjectMapper().readValue(input, new TypeReference<List<SharedCase>>() {});
        }
    }

    /** One row of {@code duration-cases.json}. */
    static final class SharedCase {
        public String input;
        public Long microseconds;
        public String canonical;
        public String rejection;

        @Override
        public String toString() {
            return input == null ? "null" : "'" + input + "'";
        }
    }

    @Test
    void rejectsBareNumbersAndSubMicrosecondDurations() {
        assertThatThrownBy(() -> DurationUtils.parse(30))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("30")
                .hasMessageContaining("bare number");
        assertThatThrownBy(() -> DurationUtils.parse(Duration.ofNanos(500)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("finer than 1 us");
    }

    @Test
    void optionDefaultsMatchTheDisableSentinels() {
        assertThat(AgentExecutionOptions.RETRY_WAIT_INTERVAL.getKey())
                .isEqualTo("retry-wait-interval");
        assertThat(AgentExecutionOptions.RETRY_WAIT_INTERVAL.getDefaultValue())
                .isEqualTo(Duration.ofSeconds(1));
        assertThat(AgentExecutionOptions.ASYNC_BATCH_TIMEOUT.getKey())
                .isEqualTo("async.batch.timeout");
        assertThat(AgentExecutionOptions.ASYNC_BATCH_TIMEOUT.getDefaultValue())
                .isEqualTo(Duration.ofMillis(-1));
        assertThat(
                        DurationUtils.isPositive(
                                AgentExecutionOptions.ASYNC_BATCH_TIMEOUT.getDefaultValue()))
                .isFalse();
        assertThat(AgentExecutionOptions.SHORT_TERM_MEMORY_STATE_TTL.getKey())
                .isEqualTo("short-term-memory.state-ttl");
        assertThat(AgentExecutionOptions.SHORT_TERM_MEMORY_STATE_TTL.getDefaultValue())
                .isEqualTo(Duration.ZERO);
        assertThat(DurationUtils.zeroIfNegative(Duration.ofMillis(-1))).isEqualTo(Duration.ZERO);
        assertThat(DurationUtils.zeroIfNegative(Duration.ofSeconds(2)))
                .isEqualTo(Duration.ofSeconds(2));
    }

    /** Deadline budget shared with Python {@code positive_seconds}. 1_500_000 ns is 0.0015 s. */
    @ParameterizedTest
    @CsvSource({
        "1500 us, 1500000",
        "1 ms, 1000000",
        "1500 ms, 1500000000",
        "1 s, 1000000000",
        "1 min, 60000000000"
    })
    void positiveDeadlineKeepsMicroseconds(String text, long nanos) {
        Duration parsed = DurationUtils.parse(text);
        assertThat(DurationUtils.positiveNanos(parsed)).isEqualTo(nanos);
    }

    @Test
    void nonPositiveDeadlineContributesNoNanos() {
        assertThat(DurationUtils.positiveNanos(null)).isZero();
        assertThat(DurationUtils.positiveNanos(DurationUtils.parse("0 ms"))).isZero();
        assertThat(DurationUtils.positiveNanos(DurationUtils.parse("-1 ms"))).isZero();
    }
}
