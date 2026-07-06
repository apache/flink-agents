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
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.agents.api.tools.ToolParameterInjection;
import org.apache.flink.agents.api.yaml.Language;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Declarative function tool. */
@JsonIgnoreProperties(ignoreUnknown = false)
public final class ToolSpec {
    private final String name;
    private final String function;
    private final Language type;
    private final List<String> parameterTypes;
    private final Map<String, ToolParameterInjection> injectedArgs;

    @JsonCreator
    public ToolSpec(
            @JsonProperty(value = "name", required = true) String name,
            @JsonProperty("function") String function,
            @JsonProperty("type") Language type,
            @JsonProperty("parameter_types") List<String> parameterTypes,
            @JsonProperty("injected_args") JsonNode injectedArgs) {
        this.name = name;
        this.function = function;
        this.type = type;
        this.parameterTypes = parameterTypes;
        this.injectedArgs = parseInjectedArgs(injectedArgs);
    }

    public String getName() {
        return name;
    }

    public String getFunction() {
        return function;
    }

    public Language getType() {
        return type;
    }

    public List<String> getParameterTypes() {
        return parameterTypes;
    }

    public Map<String, ToolParameterInjection> getInjectedArgs() {
        return injectedArgs;
    }

    private static Map<String, ToolParameterInjection> parseInjectedArgs(JsonNode node) {
        Map<String, ToolParameterInjection> result = new LinkedHashMap<>();
        if (node == null || node.isNull()) {
            return Map.of();
        }
        if (!node.isObject()) {
            throw new IllegalArgumentException("'injected_args' must be an object.");
        }
        ObjectMapper mapper = new ObjectMapper();
        node.fields()
                .forEachRemaining(
                        entry -> {
                            try {
                                result.put(
                                        entry.getKey(),
                                        mapper.treeToValue(
                                                        entry.getValue(),
                                                        ToolParameterInjection.class)
                                                .withDefaultKey(entry.getKey()));
                            } catch (Exception e) {
                                throw new IllegalArgumentException(
                                        "Failed to parse injected_args." + entry.getKey(), e);
                            }
                        });
        return Map.copyOf(result);
    }
}
