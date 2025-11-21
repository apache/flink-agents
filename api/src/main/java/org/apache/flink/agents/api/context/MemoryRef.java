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

import java.io.Serializable;
import java.util.Objects;

/**
 * A serializable, persistent reference to a specific data item in Short-Term Memory. It acts as a
 * lightweight pointer, containing the path of the data, allowing for efficient passing of large
 * objects between Actions.
 */
public final class MemoryRef implements Serializable {
    private static final long serialVersionUID = 1L;

    private final String path;

    private MemoryRef(String path) {
        this.path = path;
    }

    /**
     * Creates a new MemoryRef instance with the given path.
     *
     * @param path The absolute path of the data in Short-Term Memory.
     * @return A new MemoryRef instance.
     */
    public static MemoryRef create(String path) {
        return new MemoryRef(path);
    }

    /**
     * Resolves the reference using the provided RunnerContext to get the actual data.
     *
     * @param memory The memory this ref based on.
     * @return The deserialized, original data object.
     * @throws Exception if the memory cannot be accessed or the data cannot be resolved.
     */
    public MemoryObject resolve(MemoryObject memory) throws Exception {
        return memory.get(this);
    }

    public String getPath() {
        return path;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        MemoryRef memoryRef = (MemoryRef) o;
        return path.equals(memoryRef.path);
    }

    @Override
    public int hashCode() {
        return Objects.hash(path);
    }

    @Override
    public String toString() {
        return "MemoryRef{" + "path='" + path + '\'' + '}';
    }
}
