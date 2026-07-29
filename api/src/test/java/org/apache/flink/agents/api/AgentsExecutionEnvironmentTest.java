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

import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Unit tests for {@link AgentsExecutionEnvironment} factory methods. */
class AgentsExecutionEnvironmentTest {

    /**
     * A Flink {@link StreamExecutionEnvironment} is required. Agents run on Flink and there is no
     * in-process Java environment, so a null env must fail fast at the factory, with a message
     * naming the missing argument, rather than later.
     */
    @Test
    void getExecutionEnvironmentRejectsNullStreamEnv() {
        assertThatThrownBy(
                        () ->
                                AgentsExecutionEnvironment.getExecutionEnvironment(
                                        (StreamExecutionEnvironment) null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("StreamExecutionEnvironment");
        assertThatThrownBy(() -> AgentsExecutionEnvironment.getExecutionEnvironment(null, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("StreamExecutionEnvironment");
    }
}
