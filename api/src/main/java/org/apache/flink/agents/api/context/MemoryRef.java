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

import org.apache.flink.annotation.VisibleForTesting;

import java.io.Serializable;
import java.util.Objects;

/**
 * A serializable, persistent reference to a specific data item in Short-Term Memory. It acts as a
 * lightweight pointer, containing the path of the data, allowing for efficient passing of large
 * objects between Actions.
 */
public final class MemoryRef implements Serializable {
    private static final long serialVersionUID = 1L;

    private final MemoryObject.MemoryType type;
    private final String path;

    private MemoryRef(String path) {
        this(MemoryObject.MemoryType.SHORT_TERM, path);
    }

    private MemoryRef(MemoryObject.MemoryType type, String path) {
        this.type = type;
        this.path = path;
    }

    /**
     * Creates a new MemoryRef instance with the given path.
     *
     * @param path The absolute path of the data in Short-Term Memory.
     * @return A new MemoryRef instance.
     */
    public static MemoryRef create(MemoryObject.MemoryType type, String path) {
        return new MemoryRef(type, path);
    }

    @VisibleForTesting
    public static MemoryRef create(String path) {
        return new MemoryRef(path);
    }

    /**
     * Resolves the reference using the provided RunnerContext to get the actual data.
     *
     * @param ctx The current execution context, used to access Short-Term Memory.
     * @return The deserialized, original data object.
     * @throws Exception if the memory cannot be accessed or the data cannot be resolved.
     */
    public MemoryObject resolve(RunnerContext ctx) throws Exception {
        if (type.equals(MemoryObject.MemoryType.SENSORY)) {
            return ctx.getSensoryMemory().get(this);
        } else if (type.equals(MemoryObject.MemoryType.SHORT_TERM)) {
            return ctx.getShortTermMemory().get(this);
        } else {
            throw new RuntimeException(String.format("Unknown memory type %s", type));
        }
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
