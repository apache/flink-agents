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

package org.apache.flink.agents.api.yaml.spec;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import org.apache.flink.agents.api.yaml.Language;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Action referencing a user function plus its trigger conditions.
 *
 * <p>Each entry in {@code trigger_conditions} is either an event-type name or a condition
 * expression. Entries are classified and validated during {@code AgentPlan} construction.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public final class ActionSpec {
    private final String name;
    private final String function;
    private final List<String> triggerConditions;
    private final Map<String, Object> config;
    private final Language type;

    @JsonCreator
    private ActionSpec(
            @JsonProperty(value = "name", required = true) String name,
            @JsonProperty("function") String function,
            @JsonProperty(value = "trigger_conditions", required = true)
                    JsonNode triggerConditionsNode,
            @JsonProperty("config") Map<String, Object> config,
            @JsonProperty("type") Language type) {
        this(name, function, parseTriggerConditions(name, triggerConditionsNode), config, type);
    }

    public ActionSpec(
            String name,
            String function,
            List<String> triggerConditions,
            Map<String, Object> config,
            Language type) {
        if (triggerConditions == null || triggerConditions.isEmpty()) {
            throw new IllegalArgumentException(
                    "'trigger_conditions' is required and must contain at least one entry");
        }
        this.name = name;
        this.function = function;
        this.triggerConditions = triggerConditions;
        this.config = config;
        this.type = type;
    }

    private static List<String> parseTriggerConditions(
            String actionName, JsonNode triggerConditionsNode) {
        if (triggerConditionsNode == null || triggerConditionsNode.isNull()) {
            return null;
        }
        if (!triggerConditionsNode.isArray()) {
            throw new IllegalArgumentException("'trigger_conditions' must be an array");
        }

        List<String> triggerConditions = new ArrayList<>(triggerConditionsNode.size());
        for (int index = 0; index < triggerConditionsNode.size(); index++) {
            JsonNode entry = triggerConditionsNode.get(index);
            if (entry == null || entry.isNull()) {
                triggerConditions.add(null);
            } else if (entry.isTextual()) {
                triggerConditions.add(entry.textValue());
            } else {
                throw new IllegalArgumentException(
                        String.format(
                                "'trigger_conditions' entry #%d for action '%s' must be a string, but found %s",
                                index + 1, actionName, entry.getNodeType()));
            }
        }
        return triggerConditions;
    }

    public String getName() {
        return name;
    }

    public String getFunction() {
        return function;
    }

    public List<String> getTriggerConditions() {
        return triggerConditions;
    }

    public Map<String, Object> getConfig() {
        return config;
    }

    public Language getType() {
        return type;
    }
}
