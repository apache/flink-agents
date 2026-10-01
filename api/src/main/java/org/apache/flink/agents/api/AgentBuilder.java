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
import org.apache.flink.api.common.typeinfo.TypeInformation;
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
     * Apply an agent previously registered on the environment (typically via {@code
     * env.loadYaml(...)}) by name.
     *
     * <p>Default implementation throws — concrete builders that have access to the environment
     * override this to look up the named agent and delegate to {@link #apply(Agent)}.
     *
     * @param agentName the name under which the agent was registered on the environment.
     * @return a configured AgentBuilder for method chaining.
     */
    default AgentBuilder apply(String agentName) {
        throw new UnsupportedOperationException(
                "apply(String) is not supported by this AgentBuilder; only Agent instances accepted.");
    }

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
     * processed in the Flink pipeline. The returned view is unrestricted: its elements are whatever
     * the agent emitted ({@code Object}). To obtain a typed view, declare the output type with
     * {@link #toDataStream(TypeInformation)} or {@link #toDataStream(Class)}.
     *
     * @return DataStream containing outputs from agent execution.
     */
    DataStream<Object> toDataStream();

    /**
     * Get output DataStream of agent execution, typed as {@code T}.
     *
     * <p>The agent operator keeps emitting {@code Object} on the shared raw stream and the declared
     * type is applied by a downstream conversion operator, so the unrestricted {@link
     * #toDataStream()} view is unaffected and heterogeneous output stays available through it. Each
     * call is independent, so several typed views of different types can coexist on one execution.
     *
     * @param typeInformation the declared type of the agent's output elements.
     * @param <T> the declared output element type.
     * @return DataStream whose elements are typed as {@code T}.
     */
    <T> DataStream<T> toDataStream(TypeInformation<T> typeInformation);

    /**
     * Get output DataStream of agent execution, typed as {@code T}, deriving the {@link
     * TypeInformation} from the given output class.
     *
     * <p>Convenience overload for the common case. Prefer {@link #toDataStream(TypeInformation)}
     * for generic or custom types that a {@link Class} cannot express.
     *
     * @param outputType the declared class of the agent's output elements.
     * @param <T> the declared output element type.
     * @return DataStream whose elements are typed as {@code T}.
     */
    default <T> DataStream<T> toDataStream(Class<T> outputType) {
        return toDataStream(TypeInformation.of(outputType));
    }

    /**
     * Get output Table of agent execution, materializing the unrestricted view with an explicit
     * physical schema.
     *
     * <p>The table's row type is derived from the schema's physical columns, and the Table planner
     * adapts each agent output element into a matching row. Computed and metadata columns declared
     * in the schema are derived by the planner rather than read from the agent output. To declare
     * the output with a type instead of a physical schema, use {@link #toTable(TypeInformation)} or
     * {@link #toTable(Class)}.
     *
     * @param schema Schema indicating the structure of the output table.
     * @return Table containing outputs from agent execution.
     */
    Table toTable(Schema schema);

    /**
     * Get output Table of agent execution, typed as {@code T}, deriving the physical schema from
     * the declared type (for example, a POJO's fields become columns).
     *
     * <p>The declared type is applied by a downstream conversion operator on the shared raw stream,
     * so the unrestricted {@link #toDataStream()} view is unaffected.
     *
     * @param typeInformation the declared type of the agent's output elements.
     * @param <T> the declared output element type.
     * @return Table containing the agent's output, typed as {@code T}.
     */
    <T> Table toTable(TypeInformation<T> typeInformation);

    /**
     * Get output Table of agent execution, typed as {@code T}, deriving the {@link TypeInformation}
     * from the given output class.
     *
     * <p>Convenience overload for the common case. Prefer {@link #toTable(TypeInformation)} for
     * generic or custom types that a {@link Class} cannot express.
     *
     * @param outputType the declared class of the agent's output elements.
     * @param <T> the declared output element type.
     * @return Table containing the agent's output, typed as {@code T}.
     */
    default <T> Table toTable(Class<T> outputType) {
        return toTable(TypeInformation.of(outputType));
    }
}
