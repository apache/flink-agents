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

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.apache.flink.agents.api.yaml.Language;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Schema for any ResourceDescriptor-backed resource. Required: {@code name} and {@code clazz}.
 * {@code type} optionally pins the language. All remaining properties are captured into {@link
 * #getExtras()} and forwarded as ResourceDescriptor init args by the loader.
 */
public final class DescriptorSpec {
    private final String name;
    private final String clazz;
    private final Language type;
    private final Map<String, Object> extras = new LinkedHashMap<>();

    @JsonCreator
    public DescriptorSpec(
            @JsonProperty(value = "name", required = true) String name,
            @JsonProperty(value = "clazz", required = true) String clazz,
            @JsonProperty("type") Language type) {
        this.name = name;
        this.clazz = clazz;
        this.type = type;
    }

    @JsonAnySetter
    void putExtra(String key, Object value) {
        extras.put(key, value);
    }

    public String getName() {
        return name;
    }

    public String getClazz() {
        return clazz;
    }

    public Language getType() {
        return type;
    }

    @JsonAnyGetter
    public Map<String, Object> getExtras() {
        return extras;
    }
}
