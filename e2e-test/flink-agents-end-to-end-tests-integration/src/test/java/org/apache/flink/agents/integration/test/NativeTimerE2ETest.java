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
import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.connector.file.src.FileSource;
import org.apache.flink.connector.file.src.reader.TextLineInputFormat;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.util.CloseableIterator;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Runs Java timer Actions against the packaged runtime and real Flink timers. */
@Timeout(60)
class NativeTimerE2ETest {
    @TempDir private Path inputDirectory;

    @ParameterizedTest(name = "parallel={0}, currentRun={1}")
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void timersDriveIdleKeysWithoutFurtherInput(boolean parallel, boolean currentRun)
            throws Exception {
        Path inputFile = inputDirectory.resolve("input.txt");
        Files.writeString(inputFile, "1\n2\n");
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.setRuntimeMode(RuntimeExecutionMode.STREAMING);
        env.setParallelism(2);
        DataStream<Long> inputs =
                env.fromSource(
                                FileSource.forRecordStreamFormat(
                                                new TextLineInputFormat(),
                                                new org.apache.flink.core.fs.Path(
                                                        inputFile.toUri()))
                                        .monitorContinuously(Duration.ofMillis(100))
                                        .build(),
                                WatermarkStrategy.noWatermarks(),
                                "input")
                        .setParallelism(1)
                        .map(Long::parseLong);
        AgentsExecutionEnvironment agentsEnv =
                AgentsExecutionEnvironment.getExecutionEnvironment(env);
        agentsEnv.getConfig().set(AgentExecutionOptions.PARALLEL_EXECUTION_ENABLED, parallel);
        DataStream<Object> output =
                agentsEnv
                        .fromDataStream(inputs, value -> value)
                        .apply(currentRun ? new CurrentRunTimerAgent() : new NativeTimerAgent())
                        .toDataStream();

        List<String> observed = new ArrayList<>();
        try (CloseableIterator<Object> results = output.collectAsync()) {
            env.executeAsync("native-timers-e2e");
            int finishedKeys = 0;
            while (finishedKeys < 2 && results.hasNext()) {
                String value = results.next().toString();
                observed.add(value);
                if (value.endsWith(":done")) {
                    finishedKeys++;
                }
            }
            assertThat(observed)
                    .containsExactlyInAnyOrder(
                            "1:tick:1",
                            "1:tick:2",
                            "1:tick:3",
                            "1:done",
                            "2:tick:1",
                            "2:tick:2",
                            "2:tick:3",
                            "2:done");
            for (String key : List.of("1", "2")) {
                assertThat(observed.stream().filter(value -> value.startsWith(key + ":")))
                        .containsExactly(
                                key + ":tick:1", key + ":tick:2", key + ":tick:3", key + ":done");
            }
        }
    }
}
