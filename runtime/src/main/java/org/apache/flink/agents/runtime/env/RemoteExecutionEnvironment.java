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

package org.apache.flink.agents.runtime.env;

import org.apache.flink.agents.api.Agent;
import org.apache.flink.agents.api.AgentBuilder;
import org.apache.flink.agents.api.AgentsExecutionEnvironment;
import org.apache.flink.agents.plan.AgentPlan;
import org.apache.flink.agents.runtime.CompileUtils;
import org.apache.flink.api.java.functions.KeySelector;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.table.api.Schema;
import org.apache.flink.table.api.Table;
import org.apache.flink.table.api.bridge.java.StreamTableEnvironment;

import java.util.List;
import java.util.Map;

/**
 * Implementation of AgentsExecutionEnvironment for remote execution with Flink.
 *
 * <p>This environment integrates agents with Flink's streaming runtime, enabling agents to process
 * DataStreams and Tables within a Flink cluster.
 */
public class RemoteExecutionEnvironment extends AgentsExecutionEnvironment {

    private final StreamExecutionEnvironment env;

    public RemoteExecutionEnvironment(StreamExecutionEnvironment env) {
        this.env = env;
    }

    @Override
    public AgentBuilder fromList(List<Object> input) {
        throw new UnsupportedOperationException(
                "RemoteExecutionEnvironment does not support fromList. Use fromDataStream or fromTable instead.");
    }

    @Override
    public <T, K> AgentBuilder fromDataStream(DataStream<T> input, KeySelector<T, K> keySelector) {
        return new RemoteAgentBuilder<>(input, keySelector, env);
    }

    @Override
    public <K> AgentBuilder fromTable(
            Table input, StreamTableEnvironment tableEnv, KeySelector<Object, K> keySelector) {
        return new RemoteAgentBuilder<>(input, tableEnv, keySelector, env);
    }

    @Override
    public void execute() throws Exception {
        env.execute();
    }

    /** Implementation of AgentBuilder for remote execution environment. */
    private static class RemoteAgentBuilder<T, K> implements AgentBuilder {

        private final DataStream<T> inputDataStream;
        private final KeySelector<T, K> keySelector;
        private final StreamExecutionEnvironment env;
        private final StreamTableEnvironment tableEnv;

        private AgentPlan agentPlan;
        private DataStream<Object> outputDataStream;

        // Constructor for DataStream input
        public RemoteAgentBuilder(
                DataStream<T> inputDataStream,
                KeySelector<T, K> keySelector,
                StreamExecutionEnvironment env) {
            this.inputDataStream = inputDataStream;
            this.keySelector = keySelector;
            this.env = env;
            this.tableEnv = null;
        }

        // Constructor for Table input
        @SuppressWarnings("unchecked")
        public RemoteAgentBuilder(
                Table inputTable,
                StreamTableEnvironment tableEnv,
                KeySelector<Object, K> keySelector,
                StreamExecutionEnvironment env) {
            this.inputDataStream = (DataStream<T>) tableEnv.toDataStream(inputTable);
            this.keySelector = (KeySelector<T, K>) keySelector;
            this.env = env;
            this.tableEnv = tableEnv;
        }

        @Override
        public AgentBuilder apply(Agent agent) {
            try {
                this.agentPlan = new AgentPlan(agent);
                return this;
            } catch (Exception e) {
                throw new RuntimeException("Failed to create agent plan from agent", e);
            }
        }

        @Override
        public List<Map<String, Object>> toList() {
            throw new UnsupportedOperationException(
                    "RemoteAgentBuilder does not support toList. Use toDataStream or toTable instead.");
        }

        @Override
        public DataStream<Object> toDataStream() {
            if (agentPlan == null) {
                throw new IllegalStateException("Must apply agent before calling toDataStream");
            }

            if (outputDataStream == null) {
                if (keySelector != null) {
                    outputDataStream =
                            CompileUtils.connectToAgent(inputDataStream, keySelector, agentPlan);
                } else {
                    // If no key selector provided, use a simple pass-through key selector
                    outputDataStream =
                            CompileUtils.connectToAgent(inputDataStream, x -> x, agentPlan);
                }
            }

            return outputDataStream;
        }

        @Override
        public Table toTable(Schema schema) {
            if (tableEnv == null) {
                throw new IllegalStateException(
                        "Table environment not available. Use fromTable() method to enable table output.");
            }

            DataStream<Object> dataStream = toDataStream();
            return tableEnv.fromDataStream(dataStream, schema);
        }
    }
}
