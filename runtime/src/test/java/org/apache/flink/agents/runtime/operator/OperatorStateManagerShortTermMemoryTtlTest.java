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

import org.apache.flink.agents.api.agents.AgentExecutionOptions;
import org.apache.flink.agents.api.configuration.DurationFormat;
import org.apache.flink.agents.plan.AgentConfiguration;
import org.apache.flink.agents.runtime.memory.MemoryObjectImpl;
import org.apache.flink.api.common.functions.RuntimeContext;
import org.apache.flink.api.common.state.MapStateDescriptor;
import org.apache.flink.api.common.state.StateTtlConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

/**
 * Short-term memory state TTL is applied when keyed state is registered. Flink measures that TTL in
 * whole milliseconds, so a positive duration below 1 ms, or a longer positive duration that is not
 * a whole number of milliseconds, must fail before {@link StateTtlConfig}.
 */
class OperatorStateManagerShortTermMemoryTtlTest {

    private static final String OPTION_KEY =
            AgentExecutionOptions.SHORT_TERM_MEMORY_STATE_TTL.getKey();

    @ParameterizedTest
    @ValueSource(strings = {"500 us", "1 us", "999 us"})
    void positiveSubMillisecondTtlIsRejected(String ttl) throws Exception {
        AgentConfiguration configuration = configuration(ttl);
        String formatted = DurationFormat.format(DurationFormat.parse(ttl));

        assertThatThrownBy(() -> shortTermMemoryDescriptor(configuration))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(OPTION_KEY + " must be at least 1 ms, got " + formatted);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0 ms", "0 s", "-1 ms", "-1 us"})
    void nonPositiveTtlDisablesExpiration(String ttl) throws Exception {
        StateTtlConfig ttlConfig = shortTermMemoryDescriptor(configuration(ttl)).getTtlConfig();

        assertThat(ttlConfig.isEnabled()).isFalse();
    }

    @Test
    void unsetAndNullTtlDisableExpiration() throws Exception {
        assertThat(shortTermMemoryDescriptor(new AgentConfiguration()).getTtlConfig().isEnabled())
                .isFalse();

        AgentConfiguration nullTtl =
                new AgentConfiguration(Collections.singletonMap(OPTION_KEY, null));
        assertThat(shortTermMemoryDescriptor(nullTtl).getTtlConfig().isEnabled()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"1500 us", "1001 us"})
    void positiveTtlWithASubMillisecondRemainderIsRejected(String ttl) throws Exception {
        AgentConfiguration configuration = configuration(ttl);
        String formatted = DurationFormat.format(DurationFormat.parse(ttl));

        assertThatThrownBy(() -> shortTermMemoryDescriptor(configuration))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(
                        OPTION_KEY
                                + " must be a whole number of milliseconds, or zero or negative to"
                                + " disable TTL, got "
                                + formatted);
    }

    @ParameterizedTest
    @ValueSource(strings = {"1 ms", "1000 us", "1 min"})
    void millisecondOrLongerTtlEnablesExpiration(String ttl) throws Exception {
        StateTtlConfig ttlConfig = shortTermMemoryDescriptor(configuration(ttl)).getTtlConfig();

        assertThat(ttlConfig.isEnabled()).isTrue();
        assertThat(ttlConfig.getTimeToLive()).isEqualTo(DurationFormat.parse(ttl));
    }

    private static AgentConfiguration configuration(String ttl) {
        return new AgentConfiguration(Map.of(OPTION_KEY, ttl));
    }

    @SuppressWarnings("unchecked")
    private static MapStateDescriptor<String, MemoryObjectImpl.MemoryItem>
            shortTermMemoryDescriptor(AgentConfiguration configuration) throws Exception {
        RuntimeContext runtimeContext = mock(RuntimeContext.class);
        List<MapStateDescriptor<?, ?>> descriptors = new ArrayList<>();
        doAnswer(
                        invocation -> {
                            descriptors.add(invocation.getArgument(0));
                            return null;
                        })
                .when(runtimeContext)
                .getMapState(any(MapStateDescriptor.class));
        new OperatorStateManager().initializeKeyedStates(runtimeContext, configuration);
        return (MapStateDescriptor<String, MemoryObjectImpl.MemoryItem>)
                descriptors.stream()
                        .filter(descriptor -> "shortTermMemory".equals(descriptor.getName()))
                        .findFirst()
                        .orElseThrow();
    }
}
