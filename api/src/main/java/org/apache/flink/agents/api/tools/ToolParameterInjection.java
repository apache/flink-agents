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
package org.apache.flink.agents.api.tools;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.io.Serializable;
import java.util.Objects;

/** Declarative source binding for a framework-injected tool parameter. */
public final class ToolParameterInjection implements Serializable {
    private final ToolParameterSource source;
    private final String key;

    @JsonCreator
    public ToolParameterInjection(
            @JsonProperty("source") ToolParameterSource source, @JsonProperty("key") String key) {
        this.source = source == null ? ToolParameterSource.SENSORY_MEMORY : source;
        this.key = key;
    }

    public static ToolParameterInjection fromConfig(String key) {
        return new ToolParameterInjection(ToolParameterSource.CONFIG, key);
    }

    public static ToolParameterInjection fromSensoryMemory(String path) {
        return new ToolParameterInjection(ToolParameterSource.SENSORY_MEMORY, path);
    }

    public static ToolParameterInjection fromShortTermMemory(String path) {
        return new ToolParameterInjection(ToolParameterSource.SHORT_TERM_MEMORY, path);
    }

    public ToolParameterSource getSource() {
        return source;
    }

    public String getKey() {
        return key;
    }

    public ToolParameterInjection withDefaultKey(String defaultKey) {
        if (key != null && !key.isEmpty()) {
            return this;
        }
        return new ToolParameterInjection(source, defaultKey);
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof ToolParameterInjection)) {
            return false;
        }
        ToolParameterInjection that = (ToolParameterInjection) o;
        return source == that.source && Objects.equals(key, that.key);
    }

    @Override
    public int hashCode() {
        return Objects.hash(source, key);
    }

    @Override
    public String toString() {
        return "ToolParameterInjection{" + "source=" + source + ", key='" + key + '\'' + '}';
    }
}
