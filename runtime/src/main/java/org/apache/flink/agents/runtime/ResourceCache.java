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
import org.apache.flink.agents.plan.resource.python.PythonResourceAdapter;
import org.apache.flink.agents.plan.resourceprovider.PythonResourceProvider;
import org.apache.flink.agents.plan.resourceprovider.ResourceProvider;
import org.apache.flink.agents.plan.tools.FunctionTool;
import org.apache.flink.agents.runtime.python.utils.PythonActionExecutor;
import org.apache.flink.agents.runtime.python.utils.PythonInterpreterManager;
import org.apache.flink.agents.runtime.resource.ResourceContextImpl;
import org.apache.flink.agents.runtime.subagent.BaseSubagentSetup;
import org.apache.flink.util.ExceptionUtils;

import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

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
    private final ResourceCache parent;

    /**
     * Child plan JSON identifying the sub-agent scope this cache serves, or {@code null} for the
     * root cache. Handed to the Python runtime so it materializes the scope's Python-owned
     * resources against the child plan instead of the root plan.
     */
    @Nullable private final String scopePlanJson;

    /**
     * Construct a cache that resolves {@code classpath:} skill sources via {@code classLoader}.
     * Production code passes the Flink user-code class loader (from {@code
     * ActionExecutionOperator.getRuntimeContext().getUserCodeClassLoader()}); tests may call {@link
     * #ResourceCache(Map)}.
     */
    public ResourceCache(
            Map<ResourceType, Map<String, ResourceProvider>> resourceProviders,
            ClassLoader classLoader) {
        this(resourceProviders, classLoader, () -> null);
    }

    /** The interpreter becomes available during operator initialization, before resource use. */
    public ResourceCache(
            Map<ResourceType, Map<String, ResourceProvider>> resourceProviders,
            ClassLoader classLoader,
            Supplier<PythonInterpreterManager> interpreterManagerSupplier) {
        this(resourceProviders, classLoader, null, null, interpreterManagerSupplier);
    }

    /**
     * Construct a cache with a parent for resource inheritance. Resolution order: own cache → own
     * providers → parent. The parent's resources are cached in the parent; this cache's {@link
     * #close()} does not affect them.
     */
    public ResourceCache(
            Map<ResourceType, Map<String, ResourceProvider>> resourceProviders,
            ClassLoader classLoader,
            ResourceCache parent) {
        this(resourceProviders, classLoader, parent, null);
    }

    /**
     * Construct a cache for a sub-agent scope. Resolution and the Python bridge fall back to {@code
     * parent}; {@code scopePlanJson} is the child plan JSON the Python runtime materializes this
     * scope's Python-owned resources against, so they are built once in the scope rather than
     * looked up in the root plan. A scope uses a {@code () -> null} interpreter supplier, matching
     * the base-cache default, so it resolves {@code classpath:} skills through {@code classLoader}
     * without the operator's interpreter, exactly as it did before the supplier existed.
     */
    public ResourceCache(
            Map<ResourceType, Map<String, ResourceProvider>> resourceProviders,
            ClassLoader classLoader,
            ResourceCache parent,
            @Nullable String scopePlanJson) {
        this(resourceProviders, classLoader, parent, scopePlanJson, () -> null);
    }

    /**
     * Full constructor: a cache with a parent, its sub-agent scope's child plan JSON, and the
     * operator's interpreter supplier. The root cache passes a real supplier; a sub-agent scope
     * passes {@code () -> null}.
     */
    public ResourceCache(
            Map<ResourceType, Map<String, ResourceProvider>> resourceProviders,
            ClassLoader classLoader,
            ResourceCache parent,
            @Nullable String scopePlanJson,
            Supplier<PythonInterpreterManager> interpreterManagerSupplier) {
        this.parent = parent;
        this.scopePlanJson = scopePlanJson;
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
                        classLoader,
                        interpreterManagerSupplier);
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

    /**
     * The bridge that reaches the Python runtime for this cache: its own when wired, else the
     * nearest ancestor's. A sub-agent scope's cache is built with the root cache as parent and is
     * never wired directly, yet one Python runtime serves the whole plan tree, so the scope
     * materializes and resolves its Python-owned resources through the inherited bridge.
     */
    @Nullable
    private PythonActionExecutor effectivePythonActionExecutor() {
        if (pythonActionExecutor != null) {
            return pythonActionExecutor;
        }
        return parent != null ? parent.effectivePythonActionExecutor() : null;
    }

    /** The Python resource adapter in effect: this cache's own, else the nearest ancestor's. */
    @Nullable
    private PythonResourceAdapter effectivePythonResourceAdapter() {
        if (pythonResourceAdapter != null) {
            return pythonResourceAdapter;
        }
        return parent != null ? parent.effectivePythonResourceAdapter() : null;
    }

    public ResourceContextImpl getResourceContext() {
        return resourceContext;
    }

    /**
     * Checks whether a resource of the given name and type is available, without creating it.
     * Covers both registered providers and resources inserted directly into the cache via {@link
     * #put} (which have no provider).
     *
     * @param name the resource name
     * @param type the resource type
     * @return true if such a resource has a registered provider or is already cached
     */
    public boolean hasResource(String name, ResourceType type) {
        Map<String, Resource> cached = cache.get(type);
        if (cached != null && cached.containsKey(name)) {
            return true;
        }
        Map<String, ResourceProvider> providers = resourceProviders.get(type);
        return providers != null && providers.containsKey(name);
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
            if (parent != null) {
                return parent.getResource(name, type);
            }
            throw new IllegalArgumentException("Resource not found: " + name + " of type " + type);
        }
        ResourceProvider provider = providers.get(name);

        PythonResourceAdapter adapter = effectivePythonResourceAdapter();
        if (adapter != null && provider instanceof PythonResourceProvider) {
            ((PythonResourceProvider) provider).setPythonResourceAdapter(adapter);
        }

        Resource resource = provider.provide(resourceContext);

        if (resource instanceof BaseSubagentSetup) {
            // The framework owns the setup's identity: inject the resource name as its
            // subagent name.
            ((BaseSubagentSetup) resource).setSubagentName(name);
        }

        if (adapter != null && resource instanceof FunctionTool) {
            ((FunctionTool) resource).setPythonResourceAdapter(adapter);
        }

        try {
            resource.open();
        } catch (Throwable openFailure) {
            try {
                resource.close();
            } catch (Throwable closeFailure) {
                openFailure.addSuppressed(closeFailure);
            }
            ExceptionUtils.rethrowException(openFailure);
            throw new AssertionError("Unreachable after rethrowing resource open failure");
        }
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

    /** Snapshot of the resources of the given type already materialized in this cache. */
    public List<Resource> materializedResources(ResourceType type) {
        Map<String, Resource> typed = cache.get(type);
        if (typed == null) {
            return new ArrayList<>();
        }
        return new ArrayList<>(typed.values());
    }

    /**
     * Eagerly materializes every resource of the given type, wherever it lives. Java-owned
     * resources are resolved through their provider exactly like a first {@link #getResource}
     * access, while Python-owned resources are materialized in the Python runtime and represented
     * by a handle. Every instance is returned and cached, so a later lookup by name resolves to the
     * same instance. Providers are resolved in no particular order, and resource construction must
     * not depend on it.
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
            if (ResourceProvider.isPythonOwned(provider)) {
                hasPythonOwned = true;
                continue;
            }
            materialized.add(getResource(entry.getKey(), type));
        }
        if (!hasPythonOwned) {
            return materialized;
        }
        PythonActionExecutor executor = effectivePythonActionExecutor();
        checkState(
                executor != null,
                "Resources of type %s are declared in Python but no Python runtime was"
                        + " initialized for this plan, so they cannot be materialized.",
                type);
        // The Python runtime owns these resources: it built and opened them, so the handles are
        // cached as they are instead of being opened again here.
        for (Map.Entry<String, Resource> handle :
                executor.eagerMaterialize(type, scopePlanJson).entrySet()) {
            put(handle.getKey(), type, handle.getValue());
            materialized.add(handle.getValue());
        }
        return materialized;
    }

    @Override
    public void close() throws Exception {
        // Close every cached resource, then the resource context, even when an earlier close
        // fails. The first failure is rethrown with the later ones suppressed.
        //
        // The ladders catch Throwable, not Exception: ActionExecutionOperator.close() closes this
        // cache before the Python interpreter because cached resources may hold Python references,
        // so a non-Exception Throwable escaping here would leave the remaining resources open
        // while the interpreter behind them is torn down anyway. ExceptionUtils.rethrowException
        // passes Error and Exception through unchanged, so the caller still sees the original.
        Throwable firstFailure = null;
        for (Map<String, Resource> resources : cache.values()) {
            for (Resource resource : resources.values()) {
                try {
                    resource.close();
                } catch (Throwable t) {
                    firstFailure = ExceptionUtils.firstOrSuppressed(t, firstFailure);
                }
            }
        }
        cache.clear();
        try {
            resourceContext.close();
        } catch (Throwable t) {
            firstFailure = ExceptionUtils.firstOrSuppressed(t, firstFailure);
        }
        if (firstFailure != null) {
            ExceptionUtils.rethrowException(firstFailure);
        }
    }
}
