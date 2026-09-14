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
package org.apache.flink.agents.api.resource.python;

import org.apache.flink.annotation.Internal;
import org.apache.flink.util.ExceptionUtils;
import org.apache.flink.util.LambdaUtil;
import pemja.core.object.PyObject;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Owns temporary Pemja references consumed within a Java-to-Python bridge operation.
 *
 * <p>Callers may register a {@link PyObject} directly or nested in a Java map, list, or object
 * array. Every registered handle must be fully consumed before the scope closes. Values returned to
 * callers must not contain handles owned by this scope.
 */
@Internal
public final class PythonObjectScope implements AutoCloseable {

    private final Set<PyObject> ownedObjects = Collections.newSetFromMap(new IdentityHashMap<>());
    private boolean closed;

    /** Adds every {@link PyObject} reachable through the supplied bridge result to this scope. */
    public <T> T own(T value) {
        ensureOpen();
        visit(value, Collections.newSetFromMap(new IdentityHashMap<>()));
        return value;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;

        try {
            LambdaUtil.applyToAllWhileSuppressingExceptions(ownedObjects, PyObject::close);
        } catch (Exception e) {
            ExceptionUtils.rethrow(e);
        } finally {
            ownedObjects.clear();
        }
    }

    private void visit(Object value, Set<Object> visitedContainers) {
        if (value == null) {
            return;
        }
        if (value instanceof PyObject) {
            ownedObjects.add((PyObject) value);
            return;
        }
        if (value instanceof Map) {
            if (!visitedContainers.add(value)) {
                return;
            }
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                visit(entry.getKey(), visitedContainers);
                visit(entry.getValue(), visitedContainers);
            }
            return;
        }
        if (value instanceof List) {
            if (!visitedContainers.add(value)) {
                return;
            }
            for (Object element : (List<?>) value) {
                visit(element, visitedContainers);
            }
            return;
        }
        if (value instanceof Object[]) {
            if (!visitedContainers.add(value)) {
                return;
            }
            for (Object element : (Object[]) value) {
                visit(element, visitedContainers);
            }
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("PythonObjectScope is already closed.");
        }
    }
}
