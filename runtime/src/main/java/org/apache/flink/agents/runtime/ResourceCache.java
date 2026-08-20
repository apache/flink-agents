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

package org.apache.flink.agents.runtime;

import org.apache.flink.agents.api.resource.Resource;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.api.resource.python.PythonResourceAdapter;
import org.apache.flink.agents.plan.resourceprovider.PythonResourceProvider;
import org.apache.flink.agents.plan.resourceprovider.PythonSerializableResourceProvider;
import org.apache.flink.agents.plan.resourceprovider.ResourceProvider;
import org.apache.flink.agents.plan.tools.FunctionTool;
import org.apache.flink.agents.runtime.python.utils.PythonActionExecutor;
import org.apache.flink.agents.runtime.resource.ResourceContextImpl;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.apache.flink.util.Preconditions.checkState;

/**
 * Lazily resolves and caches Resource instances from ResourceProviders.
 *
 * <p>Resources are created on first access via their provider's {@code provide()} method and cached
 * for subsequent lookups. Supports recursive dependency resolution — a resource can depend on other
 * resources.
 *
 * <p>Thread-safe: resource resolution can happen on async pool threads (e.g. when {@code
 * BaseChatModelSetup.chat()} resolves connection, prompt, and tools inside a {@code
 * durableExecuteAsync} callable).
 */
public class ResourceCache implements AutoCloseable {

    private final Map<ResourceType, Map<String, ResourceProvider>> resourceProviders;
    private final Map<ResourceType, Map<String, Resource>> cache = new ConcurrentHashMap<>();
    private volatile PythonResourceAdapter pythonResourceAdapter;
    private volatile PythonActionExecutor pythonActionExecutor;
    private final ResourceContextImpl resourceContext;

    /**
     * Construct a cache that resolves {@code classpath:} skill sources via {@code classLoader}.
     * Production code passes the Flink user-code class loader (from {@code
     * ActionExecutionOperator.getRuntimeContext().getUserCodeClassLoader()}); tests may call {@link
     * #ResourceCache(Map)}.
     */
    public ResourceCache(
            Map<ResourceType, Map<String, ResourceProvider>> resourceProviders,
            ClassLoader classLoader) {
        // Defensive copy: the cache must not be affected by later mutations to the source map.
        this.resourceProviders = new HashMap<>();
        for (Map.Entry<ResourceType, Map<String, ResourceProvider>> entry :
                resourceProviders.entrySet()) {
            this.resourceProviders.put(entry.getKey(), new HashMap<>(entry.getValue()));
        }

        this.resourceContext =
                new ResourceContextImpl(
                        (name, type) -> {
                            try {
                                return this.getResource(name, type);
                            } catch (Exception e) {
                                throw new RuntimeException(e);
                            }
                        },
                        classLoader);
    }

    /** Convenience overload that uses the current thread's context class loader. */
    public ResourceCache(Map<ResourceType, Map<String, ResourceProvider>> resourceProviders) {
        this(resourceProviders, Thread.currentThread().getContextClassLoader());
    }

    void setPythonResourceAdapter(PythonResourceAdapter adapter) {
        this.pythonResourceAdapter = adapter;
    }

    /**
     * Wires the executor that reaches the Python runtime, so the cache can ask that runtime to
     * materialize the resources it owns. The runtime bridge calls this while the operator opens,
     * before any resource is resolved.
     */
    public void setPythonActionExecutor(PythonActionExecutor pythonActionExecutor) {
        this.pythonActionExecutor = pythonActionExecutor;
    }

    public ResourceContextImpl getResourceContext() {
        return resourceContext;
    }

    /**
     * Resolves a resource by name and type, creating it from its provider if not cached.
     *
     * @param name the resource name
     * @param type the resource type
     * @return the resource instance
     * @throws Exception if the resource cannot be found or created
     */
    public synchronized Resource getResource(String name, ResourceType type) throws Exception {
        Map<String, Resource> typed = cache.get(type);
        if (typed != null) {
            Resource cached = typed.get(name);
            if (cached != null) {
                return cached;
            }
        }

        Map<String, ResourceProvider> providers = resourceProviders.get(type);
        if (providers == null || !providers.containsKey(name)) {
            throw new IllegalArgumentException("Resource not found: " + name + " of type " + type);
        }
        ResourceProvider provider = providers.get(name);

        if (pythonResourceAdapter != null && provider instanceof PythonResourceProvider) {
            ((PythonResourceProvider) provider).setPythonResourceAdapter(pythonResourceAdapter);
        }

        Resource resource = provider.provide(resourceContext);

        if (pythonResourceAdapter != null && resource instanceof FunctionTool) {
            ((FunctionTool) resource).setPythonResourceAdapter(pythonResourceAdapter);
        }

        resource.open();
        cache.computeIfAbsent(type, k -> new ConcurrentHashMap<>()).put(name, resource);
        return resource;
    }

    /**
     * Puts a resource directly into the cache.
     *
     * @param name the resource name
     * @param type the resource type
     * @param resource the resource instance
     */
    public void put(String name, ResourceType type, Resource resource) {
        cache.computeIfAbsent(type, k -> new ConcurrentHashMap<>()).put(name, resource);
    }

    /**
     * Eagerly materializes every resource of the given type, wherever it lives. Java-owned
     * resources are resolved through their provider exactly like a first {@link #getResource}
     * access, while Python-owned resources are materialized in the Python runtime and represented
     * by a handle. Every instance is returned and cached, so a later lookup by name resolves to
     * the same instance. Providers are resolved in no particular order, and resource construction
     * must not depend on it.
     *
     * @param type the resource type to materialize.
     * @return the materialized resources, empty when the type has none.
     * @throws IllegalStateException if the type has Python-owned resources while the Python runtime
     *     is unavailable, which leaves them unreachable for the whole job.
     */
    public synchronized List<Resource> eagerMaterialize(ResourceType type) throws Exception {
        Map<String, ResourceProvider> providers = resourceProviders.get(type);
        List<Resource> materialized = new ArrayList<>();
        if (providers == null) {
            return materialized;
        }
        boolean hasPythonOwned = false;
        for (Map.Entry<String, ResourceProvider> entry : providers.entrySet()) {
            ResourceProvider provider = entry.getValue();
            if (provider instanceof PythonResourceProvider
                    || provider instanceof PythonSerializableResourceProvider) {
                hasPythonOwned = true;
                continue;
            }
            materialized.add(getResource(entry.getKey(), type));
        }
        if (!hasPythonOwned) {
            return materialized;
        }
        checkState(
                pythonActionExecutor != null,
                "Resources of type %s are declared in Python but the Python runtime is not"
                        + " running, so they cannot be materialized.",
                type);
        // The Python runtime owns these resources: it built and opened them, so the handles are
        // cached as they are instead of being opened again here.
        for (Map.Entry<String, Resource> handle :
                pythonActionExecutor.eagerMaterialize(type).entrySet()) {
            put(handle.getKey(), type, handle.getValue());
            materialized.add(handle.getValue());
        }
        return materialized;
    }

    @Override
    public void close() throws Exception {
        Exception firstException = null;
        for (Map<String, Resource> resources : cache.values()) {
            for (Resource resource : resources.values()) {
                try {
                    resource.close();
                } catch (Exception e) {
                    if (firstException == null) {
                        firstException = e;
                    } else {
                        firstException.addSuppressed(e);
                    }
                }
            }
        }
        cache.clear();
        try {
            resourceContext.close();
        } catch (Exception e) {
            if (firstException == null) {
                firstException = e;
            } else {
                firstException.addSuppressed(e);
            }
        }
        if (firstException != null) {
            throw firstException;
        }
    }
}
