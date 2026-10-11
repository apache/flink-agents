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

package org.apache.flink.agents.api.subagent;

import javax.annotation.Nullable;

/**
 * Optional Agent capability for declaring the caller-facing metadata of a compiled sub-agent.
 *
 * <p>When an Agent implementing this capability is registered as an {@code AGENT} resource, the
 * declared description and input schema become the metadata of the compiled internal sub-agent: the
 * description tells a caller when to delegate to it and the schema types the arguments it accepts,
 * which is what a chat model needs to call it as a tool. An agent that does not implement this
 * capability — or returns {@code null} — compiles without declared metadata and stays callable from
 * actions only.
 */
public interface SubagentMetadataProvider {

    /**
     * Returns the caller-facing description of this sub-agent: routing information for a caller
     * deciding whether to delegate, not an instruction for the sub-agent itself.
     *
     * @return the description, or {@code null} to declare none
     */
    @Nullable
    String getSubagentDescription();

    /**
     * Returns the JSON Schema of the arguments this sub-agent accepts, as declared explicitly.
     *
     * @return the schema, or {@code null} to declare none
     */
    @Nullable
    String getSubagentInputSchema();
}
