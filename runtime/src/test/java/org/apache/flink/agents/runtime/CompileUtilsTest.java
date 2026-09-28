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
package org.apache.flink.agents.runtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.apache.flink.agents.api.configuration.AgentConfigOptions;
import org.apache.flink.agents.plan.AgentPlan;
import org.apache.flink.agents.runtime.operator.ActionExecutionOperatorTest;
import org.apache.flink.api.common.RuntimeExecutionMode;
import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.common.typeinfo.Types;
import org.apache.flink.api.connector.source.Boundedness;
import org.apache.flink.api.connector.source.lib.NumberSequenceSource;
import org.apache.flink.api.java.functions.KeySelector;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.configuration.ExecutionOptions;
import org.apache.flink.configuration.RestartStrategyOptions;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.datastream.DataStreamSource;
import org.apache.flink.streaming.api.datastream.KeyedStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.typeinfo.python.PickledByteArrayTypeInfo;
import org.apache.flink.types.Row;
import org.apache.flink.util.CloseableIterator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Tests for {@link CompileUtils}. */
public class CompileUtilsTest {

    private static final Long TEST_SEQUENCE_START = 0L;
    private static final Long TEST_SEQUENCE_END = 100L;
    private static final Long TEST_SEQUENCE_REPEAT = 3L;
    // Agent logic: x -> (x + 1) * 2
    private static final AgentPlan TEST_AGENT_PLAN =
            ActionExecutionOperatorTest.TestAgent.getAgentPlan(false);
    private static List<Long> testSequence;

    @BeforeAll
    static void setup() {
        TEST_AGENT_PLAN
                .getConfig()
                .set(AgentConfigOptions.TRACE_LOG_TARGETS, Collections.emptyList());
        testSequence = getTestSequence();
        testSequence.sort(Long::compareTo);
    }

    @Test
    void testJavaNoKeyedStreamConnectToAgent() throws Exception {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();

        DataStreamSource<Long> inputStream = env.fromData(testSequence);
        DataStream<Object> agentOutputStream =
                CompileUtils.connectToAgent(
                        inputStream,
                        new KeySelector<Long, Long>() {

                            @Override
                            public Long getKey(Long value) throws Exception {
                                return value;
                            }
                        },
                        TEST_AGENT_PLAN);
        DataStream<Long> resultStream = agentOutputStream.map(x -> (long) x + 1);

        List<Long> resultList = new ArrayList<>();
        try (CloseableIterator<Long> iterator = resultStream.executeAndCollect()) {
            iterator.forEachRemaining(resultList::add);
        }
        resultList.sort(Long::compareTo);

        checkResult(resultList);
    }

    @Test
    void testJavaKeyedStreamConnectToAgent() throws Exception {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();

        KeyedStream<Long, Long> keyedInputStream = env.fromData(testSequence).keyBy(x -> x);
        DataStream<Object> workflowOutputStream =
                CompileUtils.connectToAgent(keyedInputStream, TEST_AGENT_PLAN);
        DataStream<Long> resultStream = workflowOutputStream.map(x -> (long) x + 1);

        List<Long> resultList = new ArrayList<>();
        try (CloseableIterator<Long> iterator = resultStream.executeAndCollect()) {
            iterator.forEachRemaining(resultList::add);
        }
        resultList.sort(Long::compareTo);

        checkResult(resultList);
    }

    @Test
    void processesEveryKeyWithDefaultBatchStateBackend() throws Exception {
        assertThat(
                        executeBatch(
                                ActionExecutionOperatorTest.TestAgent.getAgentPlan(false),
                                List.of(0L, 1L, 2L, 3L, 4L)))
                .containsExactly(2L, 4L, 6L, 8L, 10L);
    }

    @Test
    void processesEveryKeyWhenAutomaticModeSelectsBatchExecution() throws Exception {
        assertThat(
                        executeBounded(
                                ActionExecutionOperatorTest.TestAgent.getAgentPlan(false),
                                List.of(0L, 1L, 2L, 3L, 4L),
                                RuntimeExecutionMode.AUTOMATIC))
                .containsExactly(2L, 4L, 6L, 8L, 10L);
    }

    @Test
    void processesAsyncActionsWithDefaultBatchStateBackend() throws Exception {
        assertThat(
                        executeBatch(
                                ActionExecutionOperatorTest.TestAgent.getAsyncAgentPlan(false),
                                List.of(1L, 2L, 3L)))
                .containsExactly(20L, 40L, 60L);
    }

    private static List<Long> executeBatch(AgentPlan agentPlan, List<Long> input) throws Exception {
        return executeBounded(agentPlan, input, RuntimeExecutionMode.BATCH);
    }

    private static List<Long> executeBounded(
            AgentPlan agentPlan, List<Long> input, RuntimeExecutionMode runtimeMode)
            throws Exception {
        Configuration conf = new Configuration();
        conf.set(ExecutionOptions.RUNTIME_MODE, runtimeMode);
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment(conf);
        env.setParallelism(1);
        KeyedStream<Long, Long> keyedInputStream = env.fromData(input).keyBy(value -> value);
        DataStream<Object> output = CompileUtils.connectToAgent(keyedInputStream, agentPlan);

        List<Long> results = new ArrayList<>();
        try (CloseableIterator<Object> iterator = output.executeAndCollect()) {
            iterator.forEachRemaining(value -> results.add((Long) value));
        }
        results.sort(Long::compareTo);
        return results;
    }

    @ParameterizedTest
    @CsvSource({"AUTOMATIC, false", "AUTOMATIC, true", "BATCH, true"})
    @Timeout(60)
    void rejectsBatchStateBackendAtRuntime(
            RuntimeExecutionMode mode, boolean configureAfterConnecting) throws Exception {
        Configuration conf = new Configuration();
        conf.set(RestartStrategyOptions.RESTART_STRATEGY, "none");
        conf.set(
                ExecutionOptions.RUNTIME_MODE,
                configureAfterConnecting ? RuntimeExecutionMode.STREAMING : mode);
        try (StreamExecutionEnvironment env =
                StreamExecutionEnvironment.getExecutionEnvironment(conf)) {
            env.setParallelism(1);
            DataStream<Object> output =
                    CompileUtils.connectToAgent(
                            env.fromData(0L, 1L, 2L, 3L, 4L).keyBy(value -> value),
                            TEST_AGENT_PLAN);
            if (configureAfterConnecting) {
                env.setRuntimeMode(mode);
            }

            // Flink resolves AUTOMATIC only when generating the job graph. The guard must also
            // catch configuration changes after connectToAgent's early validation.
            assertThatThrownBy(
                            () -> {
                                try (CloseableIterator<Object> iterator =
                                        output.executeAndCollect()) {
                                    iterator.forEachRemaining(ignored -> {});
                                }
                            })
                    .hasStackTraceContaining(
                            "Flink Agents does not support Flink's batch keyed-state backend")
                    .hasStackTraceContaining("execution.batch-state-backend.enabled")
                    .hasStackTraceContaining("RuntimeExecutionMode.STREAMING");
        }
    }

    @ParameterizedTest
    @EnumSource(RuntimeExecutionMode.class)
    @Timeout(60)
    void processesAllKeysWithCompatibleStateBackend(RuntimeExecutionMode mode) throws Exception {
        Configuration conf = new Configuration();
        conf.set(ExecutionOptions.RUNTIME_MODE, mode);
        conf.set(RestartStrategyOptions.RESTART_STRATEGY, "none");
        // In STREAMING mode the default option must remain harmless. In batch execution,
        // disabling the batch-specific backend must preserve pending work across key switches.
        conf.set(ExecutionOptions.USE_BATCH_STATE_BACKEND, mode == RuntimeExecutionMode.STREAMING);
        try (StreamExecutionEnvironment env =
                StreamExecutionEnvironment.getExecutionEnvironment(conf)) {
            env.setParallelism(1);
            DataStream<Object> output =
                    CompileUtils.connectToAgent(
                            env.fromData(0L, 1L, 2L, 3L, 4L).keyBy(value -> value),
                            TEST_AGENT_PLAN);
            List<Object> results = new ArrayList<>();
            try (CloseableIterator<Object> iterator = output.executeAndCollect()) {
                iterator.forEachRemaining(results::add);
            }
            assertThat(results).containsExactlyInAnyOrder(2L, 4L, 6L, 8L, 10L);
        }
    }

    @Test
    @Timeout(60)
    void allowsAutomaticModeWithUnboundedSource() throws Exception {
        Configuration conf = new Configuration();
        conf.set(ExecutionOptions.RUNTIME_MODE, RuntimeExecutionMode.AUTOMATIC);
        conf.set(RestartStrategyOptions.RESTART_STRATEGY, "none");
        try (StreamExecutionEnvironment env =
                StreamExecutionEnvironment.getExecutionEnvironment(conf)) {
            env.setParallelism(1);
            DataStream<Object> output =
                    CompileUtils.connectToAgent(
                            env.fromSource(
                                            new UnboundedSequenceSource(),
                                            WatermarkStrategy.noWatermarks(),
                                            "unbounded-input")
                                    .keyBy(value -> value),
                            TEST_AGENT_PLAN);
            List<Object> results = new ArrayList<>();
            try (CloseableIterator<Object> iterator = output.executeAndCollect()) {
                iterator.forEachRemaining(results::add);
            }
            assertThat(results).containsExactlyInAnyOrder(2L, 4L, 6L, 8L, 10L);
        }
    }

    private static class UnboundedSequenceSource extends NumberSequenceSource {
        UnboundedSequenceSource() {
            super(0, 4);
        }

        @Override
        public Boundedness getBoundedness() {
            // Report unbounded input to exercise AUTOMATIC's streaming selection, but finish
            // after five records so the test can verify every result without cancelling the job.
            return Boundedness.CONTINUOUS_UNBOUNDED;
        }
    }

    @Test
    void detectsPickledAndExplicitByteArrayPythonKeyTypes() {
        assertThat(
                        CompileUtils.isPickledPythonFieldType(
                                Types.ROW(PickledByteArrayTypeInfo.PICKLED_BYTE_ARRAY_TYPE_INFO),
                                0))
                .isTrue();
        assertThat(
                        CompileUtils.isPickledPythonFieldType(
                                Types.ROW(Types.PRIMITIVE_ARRAY(Types.BYTE)), 0))
                .isFalse();
    }

    @Test
    void detectsPickledAndNonPickledPythonValueTypes() {
        assertThat(
                        CompileUtils.isPickledPythonFieldType(
                                Types.ROW(
                                        PickledByteArrayTypeInfo.PICKLED_BYTE_ARRAY_TYPE_INFO,
                                        PickledByteArrayTypeInfo.PICKLED_BYTE_ARRAY_TYPE_INFO),
                                1))
                .isTrue();
        assertThat(
                        CompileUtils.isPickledPythonFieldType(
                                Types.ROW(
                                        PickledByteArrayTypeInfo.PICKLED_BYTE_ARRAY_TYPE_INFO,
                                        Types.PRIMITIVE_ARRAY(Types.BYTE)),
                                1))
                .isFalse();
        assertThat(
                        CompileUtils.isPickledPythonFieldType(
                                Types.ROW(
                                        PickledByteArrayTypeInfo.PICKLED_BYTE_ARRAY_TYPE_INFO,
                                        Types.STRING),
                                1))
                .isFalse();
    }

    @Test
    void rejectsRawByteArrayPythonInputBeforeDeserializingTheAgentPlan() {
        KeyedStream<Row, Row> inputDataStream =
                createPythonInputStream(Types.PRIMITIVE_ARRAY(Types.BYTE));

        assertThatThrownBy(() -> CompileUtils.connectToAgent(inputDataStream, "not-json"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("only supports PyFlink input values")
                .hasMessageContaining("raw byte-array");
    }

    @Test
    void acceptsPickledPythonInputBeforeDeserializingTheAgentPlan() {
        KeyedStream<Row, Row> inputDataStream =
                createPythonInputStream(PickledByteArrayTypeInfo.PICKLED_BYTE_ARRAY_TYPE_INFO);

        assertThatThrownBy(() -> CompileUtils.connectToAgent(inputDataStream, "not-json"))
                .isInstanceOf(JsonProcessingException.class);
    }

    @Test
    void rejectsMalformedPythonInputType() {
        assertThatThrownBy(
                        () ->
                                CompileUtils.isPickledPythonFieldType(
                                        Types.ROW(
                                                PickledByteArrayTypeInfo
                                                        .PICKLED_BYTE_ARRAY_TYPE_INFO),
                                        1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("contain 2 fields");
    }

    @Test
    void testAgentNameUsedAsOperatorName() {
        AgentPlan namedAgentPlan =
                new AgentPlan(
                        TEST_AGENT_PLAN.getActions(),
                        TEST_AGENT_PLAN.getResourceProviders(),
                        TEST_AGENT_PLAN.getConfig(),
                        "test-agent");

        assertThat(compileOperatorName(namedAgentPlan)).isEqualTo("test-agent");
    }

    @Test
    void testMissingAgentNameUsesDefaultOperatorName() {
        assertThat(compileOperatorName(TEST_AGENT_PLAN)).isEqualTo("action-execute-operator");
    }

    private static KeyedStream<Row, Row> createPythonInputStream(TypeInformation<?> valueType) {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        TypeInformation<Row> inputType =
                Types.ROW(PickledByteArrayTypeInfo.PICKLED_BYTE_ARRAY_TYPE_INFO, valueType);
        Row input = Row.of(new byte[0], new byte[0]);
        return env.fromData(Collections.singletonList(input), inputType)
                .keyBy(value -> Row.of(value.getField(0)));
    }

    private static List<Long> getTestSequence() {
        List<Long> testSequence = new ArrayList<>();
        for (int i = 0; i < TEST_SEQUENCE_REPEAT; i++) {
            for (long j = TEST_SEQUENCE_START; j <= TEST_SEQUENCE_END; j++) {
                testSequence.add(j);
            }
        }
        return testSequence;
    }

    private static String compileOperatorName(AgentPlan agentPlan) {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        KeyedStream<Long, Long> keyedInputStream = env.fromData(1L).keyBy(value -> value);
        return CompileUtils.connectToAgent(keyedInputStream, agentPlan)
                .getTransformation()
                .getName();
    }

    private static void checkResult(List<Long> resultList) {
        List<Long> expectedResultList =
                testSequence.stream().map(x -> (x + 1) * 2 + 1).collect(Collectors.toList());

        assertThat(resultList).isEqualTo(expectedResultList);
    }
}
