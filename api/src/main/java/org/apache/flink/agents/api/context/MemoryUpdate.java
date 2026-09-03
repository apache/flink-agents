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
    private final boolean objectCreation;

    /**
     * Creates a new MemoryUpdate instance describing a value write.
     *
     * @param path the absolute path of the data in Short-Term Memory.
     * @param value the new value to set at the specified path.
     */
    public MemoryUpdate(String path, Object value) {
        this(path, value, false);
    }

    /**
     * Creates a new MemoryUpdate instance.
     *
     * @param path the absolute path of the data in Short-Term Memory.
     * @param value the new value to set at the specified path; always null when {@code
     *     objectCreation} is true.
     * @param objectCreation true if this update records the creation of a nested object rather than
     *     a value write. Absent in records written before this field existed, in which case Jackson
     *     defaults it to false, preserving their original replay behavior.
     */
    @JsonCreator
    public MemoryUpdate(
            @JsonProperty("path") String path,
            @JsonProperty("value") Object value,
            @JsonProperty("objectCreation") boolean objectCreation) {
        if (objectCreation && value != null) {
            throw new IllegalArgumentException(
                    "An object-creation update cannot carry a value, but got one for path '"
                            + path
                            + "': "
                            + value);
        }
        this.path = path;
        this.value = value;
        this.objectCreation = objectCreation;
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

    /**
     * Whether this update records the creation of a nested object (via {@code newObject}) rather
     * than a value write. Replay must re-create the object instead of setting a null value.
     *
     * @return true if this update is a nested-object creation.
     */
    public boolean isObjectCreation() {
        return objectCreation;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof MemoryUpdate)) return false;
        MemoryUpdate that = (MemoryUpdate) o;
        return objectCreation == that.objectCreation
                && Objects.equals(path, that.path)
                && Objects.equals(value, that.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(path, value, objectCreation);
    }

    @Override
    public String toString() {
        return "MemoryUpdate{"
                + "path='"
                + path
                + '\''
                + ", value="
                + value
                + ", objectCreation="
                + objectCreation
                + '}';
    }
}
