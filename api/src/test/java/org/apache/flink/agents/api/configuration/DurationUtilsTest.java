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

import org.apache.flink.agents.api.agents.AgentExecutionOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Parser and precision coverage shared with the Python duration tests. */
class DurationUtilsTest {

    @ParameterizedTest
    @CsvSource({
        "30 s, 30 s",
        "30s, 30 s",
        "5 min, 5 min",
        "5m, 5 min",
        "1 h, 1 h",
        "1 d, 1 d",
        "1500 ms, 1500 ms",
        "60 s, 1 min",
        "1000 ns, 1 us",
        "1 us, 1 us",
        "1 µs, 1 us",
        "0 ms, 0 ms",
        "0 s, 0 ms",
        "-1 ms, -1 ms",
        "-1ms, -1 ms",
        "2 seconds, 2 s",
        "1 millisecond, 1 ms"
    })
    void parsesAndFormats(String text, String canonical) {
        Duration parsed = DurationUtils.parse(text);
        assertThat(DurationUtils.format(parsed)).isEqualTo(canonical);
        assertThat(DurationUtils.parse(canonical)).isEqualTo(parsed);
        assertThat(DurationUtils.parse(parsed)).isEqualTo(parsed);
    }

    @Test
    void knownValuesMatchDurationFactories() {
        assertThat(DurationUtils.parse("30 s")).isEqualTo(Duration.ofSeconds(30));
        assertThat(DurationUtils.parse("5 min")).isEqualTo(Duration.ofMinutes(5));
        assertThat(DurationUtils.parse("1 h")).isEqualTo(Duration.ofHours(1));
        assertThat(DurationUtils.parse("1 d")).isEqualTo(Duration.ofDays(1));
        assertThat(DurationUtils.parse("1500 ms")).isEqualTo(Duration.ofMillis(1500));
        assertThat(DurationUtils.parse("1 us")).isEqualTo(Duration.ofNanos(1_000));
        assertThat(DurationUtils.parse("-1 ms")).isEqualTo(Duration.ofMillis(-1));
        assertThat(DurationUtils.parse("0 s")).isEqualTo(Duration.ZERO);
    }

    @ParameterizedTest
    @CsvSource({
        "30, a bare number",
        "1.5 s, an integer",
        "1 fortnight, not recognized",
        "1 ns, finer than 1 us",
        "'', a number and a unit",
        "-, an integer"
    })
    void rejectsUnusableText(String text, String reason) {
        assertThatThrownBy(() -> DurationUtils.parse(text))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(reason);
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

    @Test
    void wholeMillisecondsRejectASubMillisecondRemainder() {
        assertThatThrownBy(
                        () ->
                                DurationUtils.requireWholeMilliseconds(
                                        "retry-wait-interval",
                                        DurationUtils.parse("1500 us"),
                                        "or zero or negative to retry without waiting"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(
                        "retry-wait-interval must be a whole number of milliseconds,"
                                + " or zero or negative to retry without waiting, got 1500 us");

        Duration wait =
                DurationUtils.requireWholeMilliseconds(
                        "retry-wait-interval",
                        DurationUtils.parse("1500 ms"),
                        "or zero or negative to retry without waiting");
        assertThat(wait).isEqualTo(Duration.ofMillis(1500));
        assertThat(wait.toNanos()).isEqualTo(wait.toMillis() * 1_000_000L);
        assertThat(
                        DurationUtils.requireWholeMilliseconds(
                                "retry-wait-interval",
                                DurationUtils.parse("-1 ms"),
                                "or zero or negative to retry without waiting"))
                .isEqualTo(Duration.ofMillis(-1));
    }
}
