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

import org.apache.flink.agents.api.AgentBuilder;
import org.apache.flink.agents.api.AgentsExecutionEnvironment;
import org.apache.flink.agents.api.agents.Agent;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.plan.AgentConfiguration;
import org.apache.flink.agents.plan.AgentPlan;
import org.apache.flink.agents.runtime.CompileUtils;
import org.apache.flink.api.java.functions.KeySelector;
import org.apache.flink.configuration.ConfigConstants;
import org.apache.flink.configuration.YamlParserUtils;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.table.api.Schema;
import org.apache.flink.table.api.Table;
import org.apache.flink.table.api.bridge.java.StreamTableEnvironment;

import javax.annotation.Nullable;

import java.io.File;
import java.util.HashMap;
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
    private @Nullable StreamTableEnvironment tEnv;

    private final AgentConfiguration config;

    public static final String FLINK_CONF_FILENAME = "config.yaml";

    public RemoteExecutionEnvironment(
            StreamExecutionEnvironment env, @Nullable StreamTableEnvironment tEnv) {
        this.env = env;
        this.tEnv = tEnv;
        final String configDir = System.getenv(ConfigConstants.ENV_FLINK_CONF_DIR);
        this.config = loadAgentConfiguration(configDir);
    }

    private StreamTableEnvironment getTableEnvironment() {
        if (tEnv == null) {
            tEnv = StreamTableEnvironment.create(env);
        }
        return tEnv;
    }

    @Override
    public AgentConfiguration getConfig() {
        return config;
    }

    @Override
    public AgentBuilder fromList(List<Object> input) {
        throw new UnsupportedOperationException(
                "RemoteExecutionEnvironment does not support fromList. Use fromDataStream or fromTable instead.");
    }

    @Override
    public <T, K> AgentBuilder fromDataStream(DataStream<T> input, KeySelector<T, K> keySelector) {
        return new RemoteAgentBuilder<>(input, tEnv, keySelector, env, config, resources);
    }

    @Override
    public <K> AgentBuilder fromTable(Table input, KeySelector<Object, K> keySelector) {
        return new RemoteAgentBuilder<>(
                input, getTableEnvironment(), keySelector, env, config, resources);
    }

    @Override
    public void execute() throws Exception {
        env.execute();
    }

    @SuppressWarnings("unchecked")
    public static AgentConfiguration loadAgentConfiguration(String configDir) {
        try {
            if (configDir == null) {
                return new AgentConfiguration();
            }
            final Map<String, Object> configData =
                    (Map<String, Object>)
                            YamlParserUtils.loadYamlFile(new File(configDir, FLINK_CONF_FILENAME))
                                    .getOrDefault("agent", new HashMap<>());

            return new AgentConfiguration(configData);
        } catch (Exception e) {
            throw new RuntimeException(
                    "Failed to load Flink Agents configuration from " + configDir, e);
        }
    }

    /** Implementation of AgentBuilder for remote execution environment. */
    private static class RemoteAgentBuilder<T, K> implements AgentBuilder {

        private final DataStream<T> inputDataStream;
        private final KeySelector<T, K> keySelector;
        private final StreamExecutionEnvironment env;
        private @Nullable StreamTableEnvironment tableEnv;
        private final AgentConfiguration config;
        private final Map<ResourceType, Map<String, Object>> resources;

        private AgentPlan agentPlan;
        private DataStream<Object> outputDataStream;

        // Constructor for DataStream input
        public RemoteAgentBuilder(
                DataStream<T> inputDataStream,
                @Nullable StreamTableEnvironment tableEnv,
                KeySelector<T, K> keySelector,
                StreamExecutionEnvironment env,
                AgentConfiguration config,
                Map<ResourceType, Map<String, Object>> resources) {
            this.inputDataStream = inputDataStream;
            this.keySelector = keySelector;
            this.env = env;
            this.tableEnv = tableEnv;
            this.config = config;
            this.resources = resources;
        }

        // Constructor for Table input
        @SuppressWarnings("unchecked")
        public RemoteAgentBuilder(
                Table inputTable,
                StreamTableEnvironment tableEnv,
                KeySelector<Object, K> keySelector,
                StreamExecutionEnvironment env,
                AgentConfiguration config,
                Map<ResourceType, Map<String, Object>> resources) {
            this.inputDataStream = (DataStream<T>) tableEnv.toDataStream(inputTable);
            this.keySelector = (KeySelector<T, K>) keySelector;
            this.env = env;
            this.tableEnv = tableEnv;
            this.config = config;
            this.resources = resources;
        }

        private StreamTableEnvironment getTableEnvironment() {
            if (tableEnv == null) {
                tableEnv = StreamTableEnvironment.create(env);
            }
            return tableEnv;
        }

        @Override
        public AgentBuilder apply(Agent agent) {
            try {
                // Inspect resources registered in environment to agent.
                agent.addResourcesIfAbsent(resources);
                this.agentPlan = new AgentPlan(agent, config);
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
            DataStream<Object> dataStream = toDataStream();
            return getTableEnvironment().fromDataStream(dataStream, schema);
        }
    }
}
