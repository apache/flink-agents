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
package org.apache.flink.agents.api.context;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

import java.io.Serializable;
import java.util.Objects;

@JsonSerialize()
@JsonDeserialize()
public class MemoryUpdate implements Serializable {
    private static final long serialVersionUID = 1L;

    private final String path;
    private final Object value;

    /**
     * Creates a new MemoryUpdate instance.
     *
     * @param path the absolute path of the data in Short-Term Memory.
     * @param value the new value to set at the specified path.
     */
    @JsonCreator
    public MemoryUpdate(@JsonProperty("path") String path, @JsonProperty("value") Object value) {
        this.path = path;
        this.value = value;
    }

    /**
     * Gets the path of the memory update.
     *
     * @return the absolute path of the data in Short-Term Memory.
     */
    public String getPath() {
        return path;
    }

    /**
     * Gets the value of the memory update.
     *
     * @return the new value to set at the specified path.
     */
    public Object getValue() {
        return value;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof MemoryUpdate)) return false;
        MemoryUpdate that = (MemoryUpdate) o;
        return Objects.equals(path, that.path) && Objects.equals(value, that.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(path, value);
    }

    @Override
    public String toString() {
        return "MemoryUpdate{" + "path='" + path + '\'' + ", value=" + value + '}';
    }
}
