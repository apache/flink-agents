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

import org.apache.flink.agents.api.configuration.Configuration;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.api.resource.SerializableResource;
import org.apache.flink.api.java.functions.KeySelector;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.table.api.Table;
import org.apache.flink.table.api.bridge.java.StreamTableEnvironment;

import javax.annotation.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Base class for agent execution environment.
 *
 * <p>This class provides the main entry point for integrating Flink Agents with different types of
 * Flink data sources (DataStream, Table, or simple lists).
 */
public abstract class AgentsExecutionEnvironment {
    protected final Map<ResourceType, Map<String, Object>> resources;

    protected AgentsExecutionEnvironment() {
        this.resources = new HashMap<>();
        for (ResourceType type : ResourceType.values()) {
            this.resources.put(type, new HashMap<>());
        }
    }

    /**
     * Get agents execution environment.
     *
     * <p>Factory method that creates an appropriate execution environment based on the provided
     * StreamExecutionEnvironment. If no environment is provided, a local execution environment is
     * returned for testing and development.
     *
     * <p>When integrating with Flink DataStream/Table APIs, users should pass the Flink
     * StreamExecutionEnvironment to enable remote execution capabilities.
     *
     * @param env Optional StreamExecutionEnvironment for remote execution. If null, a local
     *     execution environment will be created.
     * @param tEnv Optional StreamTableEnvironment for table-to-stream conversion.
     * @return AgentsExecutionEnvironment appropriate for the execution context.
     */
    public static AgentsExecutionEnvironment getExecutionEnvironment(
            StreamExecutionEnvironment env, @Nullable StreamTableEnvironment tEnv) {
        if (env == null) {
            // Return local execution environment for testing/development
            try {
                Class<?> localEnvClass =
                        Class.forName(
                                "org.apache.flink.agents.runtime.env.LocalExecutionEnvironment");
                return (AgentsExecutionEnvironment)
                        localEnvClass.getDeclaredConstructor().newInstance();
            } catch (Exception e) {
                throw new RuntimeException("Failed to create LocalExecutionEnvironment", e);
            }
        } else {
            // Return remote execution environment for Flink integration
            try {
                Class<?> remoteEnvClass =
                        Class.forName(
                                "org.apache.flink.agents.runtime.env.RemoteExecutionEnvironment");
                return (AgentsExecutionEnvironment)
                        remoteEnvClass
                                .getDeclaredConstructor(
                                        StreamExecutionEnvironment.class,
                                        StreamTableEnvironment.class)
                                .newInstance(env, tEnv);
            } catch (Exception e) {
                throw new RuntimeException("Failed to create RemoteExecutionEnvironment", e);
            }
        }
    }

    /**
     * Convenience method to get execution environment without Flink StreamTableEnvironment. If
     * StreamTableEnvironment is needed during execution, the environment will auto crate using
     * StreamExecutionEnvironment.
     *
     * <p>* @param env Optional StreamExecutionEnvironment for remote execution. If null, a local
     * execution environment will be created.
     *
     * @return Remote execution environment for testing and development.
     */
    public static AgentsExecutionEnvironment getExecutionEnvironment(
            StreamExecutionEnvironment env) {
        return getExecutionEnvironment(env, null);
    }

    /**
     * Convenience method to get execution environment without Flink integration.
     *
     * @return Local execution environment for testing and development.
     */
    public static AgentsExecutionEnvironment getExecutionEnvironment() {
        return getExecutionEnvironment(null);
    }

    /**
     * Returns a writable configuration object for setting configuration values.
     *
     * @return the WritableConfiguration instance used to modify configuration settings
     */
    public abstract Configuration getConfig();

    /**
     * Set input for agents from a list. Used for local execution.
     *
     * <p>The input list elements should be objects that will be wrapped in InputEvent instances and
     * processed by the agent.
     *
     * @param input List of input objects for agent processing.
     * @return AgentBuilder for configuring the agent pipeline.
     */
    public abstract AgentBuilder fromList(List<Object> input);

    /**
     * Set input for agents from a DataStream. Used for remote execution.
     *
     * <p>This method integrates agents with Flink DataStream API, allowing agents to process
     * streaming data with optional keying for stateful operations.
     *
     * @param input DataStream to be processed by agents.
     * @param keySelector Optional KeySelector for extracting keys from input records. If null, the
     *     stream will be processed without keying.
     * @param <T> Type of elements in the input DataStream.
     * @param <K> Type of the key extracted by the KeySelector.
     * @return AgentBuilder for configuring the agent pipeline.
     */
    public abstract <T, K> AgentBuilder fromDataStream(
            DataStream<T> input, KeySelector<T, K> keySelector);

    /**
     * Set input for agents from a DataStream without keying.
     *
     * @param input DataStream to be processed by agents.
     * @param <T> Type of elements in the input DataStream.
     * @return AgentBuilder for configuring the agent pipeline.
     */
    public <T> AgentBuilder fromDataStream(DataStream<T> input) {
        return fromDataStream(input, null);
    }

    /**
     * Set input for agents from a Table. Used for remote execution.
     *
     * <p>This method integrates agents with Flink Table API, converting the table to a DataStream
     * and processing it through agents.
     *
     * @param input Table to be processed by agents.
     * @param keySelector Optional KeySelector for extracting keys from table rows.
     * @param <K> Type of the key extracted by the KeySelector.
     * @return AgentBuilder for configuring the agent pipeline.
     */
    public abstract <K> AgentBuilder fromTable(Table input, KeySelector<Object, K> keySelector);

    /**
     * Set input for agents from a Table without keying.
     *
     * @param input Table to be processed by agents.
     * @return AgentBuilder for configuring the agent pipeline.
     */
    public AgentBuilder fromTable(Table input) {
        return fromTable(input, null);
    }

    /**
     * Execute agent pipeline.
     *
     * <p>This method triggers the execution of the configured agent pipeline. For local
     * environments, this runs the agent locally. For remote environments, this delegates to the
     * underlying Flink execution environment.
     */
    public abstract void execute() throws Exception;

    /**
     * Execute agent pipeline with a given job name.
     *
     * <p>This method triggers the execution of the configured agent pipeline with the specified job
     * name.
     *
     * @param jobName The name for the Flink job.
     */
    public abstract void execute(String jobName) throws Exception;

    /**
     * Register resource to agents execution environment.
     *
     * @param name The name indicate the resource.
     * @param type The type of the resource.
     * @param instance The serializable resource object, or the resource descriptor.
     */
    public AgentsExecutionEnvironment addResource(String name, ResourceType type, Object instance) {
        if (resources.get(type).containsKey(name)) {
            throw new IllegalArgumentException(String.format("%s %s already defined.", type, name));
        }

        if (instance instanceof SerializableResource) {
            resources.get(type).put(name, instance);
        } else if (instance instanceof ResourceDescriptor) {
            resources.get(type).put(name, instance);
        } else {
            throw new IllegalArgumentException(
                    String.format("Unsupported resource %s", instance.getClass().getName()));
        }
        return this;
    }
}
