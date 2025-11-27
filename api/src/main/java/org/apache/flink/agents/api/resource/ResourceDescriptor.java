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

package org.apache.flink.agents.api.resource;

import org.apache.flink.shaded.jackson2.com.fasterxml.jackson.annotation.JsonCreator;
import org.apache.flink.shaded.jackson2.com.fasterxml.jackson.annotation.JsonProperty;
import org.apache.flink.shaded.jackson2.com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.HashMap;
import java.util.Map;

/** Helper class to describe a {@link Resource} */
public class ResourceDescriptor {
    private static final String FIELD_CLAZZ = "clazz";
    private static final String FIELD_INITIAL_ARGUMENTS = "initialArguments";

    @JsonProperty(FIELD_CLAZZ)
    private final String clazz;

    // TODO: support nested map/list with non primitive value.
    @JsonProperty(FIELD_INITIAL_ARGUMENTS)
    @JsonTypeInfo(use = JsonTypeInfo.Id.CLASS)
    private final Map<String, Object> initialArguments;

    @JsonCreator
    public ResourceDescriptor(
            @JsonProperty(FIELD_CLAZZ) String clazz,
            @JsonProperty(FIELD_INITIAL_ARGUMENTS) Map<String, Object> initialArguments) {
        this.clazz = clazz;
        this.initialArguments = initialArguments;
    }

    public String getClazz() {
        return clazz;
    }

    public Map<String, Object> getInitialArguments() {
        return initialArguments;
    }

    @SuppressWarnings("unchecked")
    public <T> T getArgument(String argName) {
        return (T) initialArguments.get(argName);
    }

    public <T> T getArgument(String argName, T defaultValue) {
        T value = getArgument(argName);
        return value != null ? value : defaultValue;
    }

    public static class Builder {
        private final String clazz;
        private final Map<String, Object> initialArguments;

        public static Builder newBuilder(String clazz) {
            return new Builder(clazz);
        }

        public Builder(String clazz) {
            this.clazz = clazz;
            this.initialArguments = new HashMap<>();
        }

        public Builder addInitialArgument(String argName, Object argValue) {
            this.initialArguments.put(argName, argValue);
            return this;
        }

        public ResourceDescriptor build() {
            return new ResourceDescriptor(clazz, initialArguments);
        }
    }
}
