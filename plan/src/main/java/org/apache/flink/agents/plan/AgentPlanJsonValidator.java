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

package org.apache.flink.agents.plan;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.annotation.Internal;

import javax.annotation.Nullable;

/**
 * Validates Python-produced AgentPlan JSON with the authoritative Java Plan implementation.
 *
 * <p>This is an internal Py4J bridge. A valid plan returns {@code null}; an expected user Plan
 * error returns one display-ready message. JVM, bridge, and other unexpected failures are allowed
 * to propagate to the caller.
 */
@Internal
public final class AgentPlanJsonValidator {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Finds a validation error in a complete serialized AgentPlan.
     *
     * @return {@code null} when valid, otherwise a display-ready user-error message
     */
    @Nullable
    public static String validateAgentPlan(String agentPlanJson) {
        try {
            MAPPER.readValue(agentPlanJson, AgentPlan.class);
            return null;
        } catch (JsonProcessingException error) {
            String message = error.getOriginalMessage();
            return message == null || message.isEmpty()
                    ? error.getClass().getSimpleName()
                    : message;
        }
    }

    private AgentPlanJsonValidator() {}
}
