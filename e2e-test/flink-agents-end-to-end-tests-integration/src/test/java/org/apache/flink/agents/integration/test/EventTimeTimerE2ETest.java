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
package org.apache.flink.agents.integration.test;

import org.apache.flink.agents.api.AgentsExecutionEnvironment;
import org.apache.flink.agents.api.agents.AgentExecutionOptions;
import org.apache.flink.api.common.RuntimeExecutionMode;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.ProcessFunction;
import org.apache.flink.util.CloseableIterator;
import org.apache.flink.util.Collector;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Timeout(60)
class EventTimeTimerE2ETest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void eventTimerResultsPrecedeTheTerminalWatermark(boolean parallel) throws Exception {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.setRuntimeMode(RuntimeExecutionMode.STREAMING);
        env.setParallelism(2);
        AgentsExecutionEnvironment agentsEnv =
                AgentsExecutionEnvironment.getExecutionEnvironment(env);
        agentsEnv.getConfig().set(AgentExecutionOptions.PARALLEL_EXECUTION_ENABLED, parallel);
        var results =
                agentsEnv
                        .fromDataStream(env.fromData(1L, 2L), value -> value)
                        .apply(new EventTimeTimerAgent())
                        .toDataStream()
                        .process(new VerifyTimestamp());
        List<String> observed = new ArrayList<>();
        try (CloseableIterator<String> iterator = results.executeAndCollect()) {
            iterator.forEachRemaining(observed::add);
        }
        assertThat(observed).containsExactlyInAnyOrder("1:10", "1:9", "2:10", "2:9");
    }

    private static class VerifyTimestamp extends ProcessFunction<Object, String> {
        @Override
        public void processElement(Object value, Context ctx, Collector<String> out) {
            String text = value.toString();
            long deadline = Long.parseLong(text.split(":")[1]);
            assertThat(ctx.timestamp()).isEqualTo(deadline);
            assertThat(ctx.timerService().currentWatermark()).isLessThan(deadline);
            out.collect(text);
        }
    }
}
