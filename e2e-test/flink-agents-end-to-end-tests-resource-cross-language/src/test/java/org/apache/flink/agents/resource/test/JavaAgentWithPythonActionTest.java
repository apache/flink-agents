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
package org.apache.flink.agents.resource.test;

import org.apache.flink.agents.api.AgentsExecutionEnvironment;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.util.CloseableIterator;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class JavaAgentWithPythonActionTest {

    @Test
    public void javaAgentDispatchesPythonActionBody() throws Exception {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.setParallelism(1);

        DataStream<Long> inputStream = env.fromData(1L, 2L, 3L, 4L, 5L);

        AgentsExecutionEnvironment agentsEnv =
                AgentsExecutionEnvironment.getExecutionEnvironment(env);

        DataStream<Object> outputStream =
                agentsEnv
                        .fromDataStream(
                                inputStream, new JavaAgentWithPythonActionAgent.SingleKeySelector())
                        .apply(new JavaAgentWithPythonActionAgent())
                        .toDataStream();

        CloseableIterator<Object> results = outputStream.collectAsync();
        agentsEnv.execute();

        List<Long> actual = new ArrayList<>();
        while (results.hasNext()) {
            actual.add(((Number) results.next()).longValue());
        }
        Collections.sort(actual);

        assertThat(actual).containsExactly(2L, 4L, 6L, 8L, 10L);
    }
}
