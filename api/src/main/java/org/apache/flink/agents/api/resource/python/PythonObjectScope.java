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

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Owns the Pemja references produced during one Java-to-Python bridge operation.
 *
 * <p>Pemja may return a {@link PyObject} directly or nested in a Java map, iterable, or object
 * array. Callers should add the complete bridge result to a short-lived scope and keep the scope
 * open until all Java conversion is complete. {@link #release(Object)} is reserved for explicitly
 * transferring a reference to a longer-lived owner.
 */
@Internal
public final class PythonObjectScope implements AutoCloseable {

    private final List<PyObject> acquisitionOrder = new ArrayList<>();
    private final Set<PyObject> ownedObjects = Collections.newSetFromMap(new IdentityHashMap<>());
    private boolean closed;

    /** Adds every {@link PyObject} reachable through the supplied bridge result to this scope. */
    public <T> T own(T value) {
        ensureOpen();
        visit(value, true);
        return value;
    }

    /** Transfers every {@link PyObject} reachable through the value out of this scope. */
    public <T> T release(T value) {
        ensureOpen();
        visit(value, false);
        return value;
    }

    /**
     * Closes a long-lived Python resource once, including both its Python lifecycle and its Pemja
     * reference.
     */
    public void closeResource(PythonResourceAdapter adapter, PyObject resource) throws Exception {
        if (resource == null || !ownedObjects.remove(resource)) {
            return;
        }

        List<AutoCloseable> closeables =
                List.of(
                        () -> {
                            try (PythonObjectScope closeResult = new PythonObjectScope()) {
                                closeResult.own(
                                        adapter.callMethod(
                                                resource, "close", Collections.emptyMap()));
                                closeResult.release(resource);
                            }
                        },
                        resource);
        LambdaUtil.applyToAllWhileSuppressingExceptions(closeables, AutoCloseable::close);
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;

        List<PyObject> references = new ArrayList<>();
        for (int i = acquisitionOrder.size() - 1; i >= 0; i--) {
            PyObject object = acquisitionOrder.get(i);
            if (ownedObjects.remove(object)) {
                references.add(object);
            }
        }
        acquisitionOrder.clear();

        try {
            LambdaUtil.applyToAllWhileSuppressingExceptions(references, PyObject::close);
        } catch (Exception e) {
            ExceptionUtils.rethrow(e);
        }
    }

    private void visit(Object value, boolean acquire) {
        Set<Object> visitedContainers = Collections.newSetFromMap(new IdentityHashMap<>());
        visit(value, acquire, visitedContainers);
    }

    private void visit(Object value, boolean acquire, Set<Object> visitedContainers) {
        if (value == null) {
            return;
        }
        if (value instanceof PyObject) {
            PyObject object = (PyObject) value;
            if (acquire) {
                if (ownedObjects.add(object)) {
                    acquisitionOrder.add(object);
                }
            } else {
                ownedObjects.remove(object);
            }
            return;
        }
        if (value instanceof Map) {
            if (!visitedContainers.add(value)) {
                return;
            }
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                visit(entry.getKey(), acquire, visitedContainers);
                visit(entry.getValue(), acquire, visitedContainers);
            }
            return;
        }
        if (value instanceof Iterable) {
            if (!visitedContainers.add(value)) {
                return;
            }
            for (Object element : (Iterable<?>) value) {
                visit(element, acquire, visitedContainers);
            }
            return;
        }
        if (value.getClass().isArray() && !value.getClass().getComponentType().isPrimitive()) {
            if (!visitedContainers.add(value)) {
                return;
            }
            for (int i = 0; i < Array.getLength(value); i++) {
                visit(Array.get(value, i), acquire, visitedContainers);
            }
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("PythonObjectScope is already closed.");
        }
    }
}
