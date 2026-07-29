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
import org.apache.flink.agents.plan.resourceprovider.ResourceProvider;
import org.apache.flink.agents.plan.tools.FunctionTool;
import org.apache.flink.agents.runtime.resource.ResourceContextImpl;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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
