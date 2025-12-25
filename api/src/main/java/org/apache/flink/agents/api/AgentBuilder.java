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

import org.apache.flink.agents.api.agents.Agent;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.table.api.Schema;
import org.apache.flink.table.api.Table;

import java.util.List;
import java.util.Map;

/**
 * Builder interface for integrating agents with input and output.
 *
 * <p>This interface provides a fluent API for configuring agents and producing different types of
 * outputs from agent execution.
 */
public interface AgentBuilder {

    /**
     * Set agent of AgentBuilder.
     *
     * @param agent The agent user defined to run in execution environment.
     * @return A configured AgentBuilder for method chaining.
     */
    AgentBuilder apply(Agent agent);

    /**
     * Get output list of agent execution.
     *
     * <p>The elements in the list represent outputs produced by the agent. Each element is a Map
     * with key-value pairs where the key represents the identifier for the input data and the value
     * is the agent's output. This method is primarily used for local execution environments.
     *
     * @return List of Map containing outputs from agent execution in the format {key: output}.
     */
    List<Map<String, Object>> toList();

    /**
     * Get output DataStream of agent execution.
     *
     * <p>This method converts the agent's output events into a Flink DataStream that can be further
     * processed in the Flink pipeline.
     *
     * @return DataStream containing outputs from agent execution.
     */
    DataStream<Object> toDataStream();

    /**
     * Get output Table of agent execution.
     *
     * <p>This method converts the agent's output events into a Flink Table using the provided
     * schema for structure definition.
     *
     * @param schema Schema indicating the structure of the output table.
     * @return Table containing outputs from agent execution.
     */
    Table toTable(Schema schema);
}
