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

import org.apache.flink.agents.plan.AgentPlan;
import org.apache.flink.agents.runtime.operator.ActionExecutionOperatorTest;
import org.apache.flink.api.java.functions.KeySelector;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.datastream.DataStreamSource;
import org.apache.flink.streaming.api.datastream.KeyedStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.util.CloseableIterator;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;

/** Tests for {@link CompileUtils}. */
public class CompileUtilsTest {

    private static final Long TEST_SEQUENCE_START = 0L;
    private static final Long TEST_SEQUENCE_END = 100L;
    // Agent logic: x -> (x + 1) * 2
    private static final AgentPlan TEST_AGENT_PLAN =
            ActionExecutionOperatorTest.TestAgent.getAgentPlan();

    @Test
    void testJavaNoKeyedStreamConnectToAgent() throws Exception {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();

        DataStreamSource<Long> inputStream =
                env.fromSequence(TEST_SEQUENCE_START, TEST_SEQUENCE_END);
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

        KeyedStream<Long, Long> keyedInputStream =
                env.fromSequence(TEST_SEQUENCE_START, TEST_SEQUENCE_END).keyBy(x -> x);
        DataStream<Object> agentOutputStream =
                CompileUtils.connectToAgent(keyedInputStream, TEST_AGENT_PLAN);
        DataStream<Long> resultStream = agentOutputStream.map(x -> (long) x + 1);

        List<Long> resultList = new ArrayList<>();
        try (CloseableIterator<Long> iterator = resultStream.executeAndCollect()) {
            iterator.forEachRemaining(resultList::add);
        }
        resultList.sort(Long::compareTo);

        checkResult(resultList);
    }

    private void checkResult(List<Long> resultList) {
        List<Long> expectedResultList =
                LongStream.rangeClosed(TEST_SEQUENCE_START, TEST_SEQUENCE_END)
                        .boxed()
                        .map(x -> (x + 1) * 2 + 1)
                        .collect(Collectors.toList());

        assertThat(resultList).isEqualTo(expectedResultList);
    }
}
