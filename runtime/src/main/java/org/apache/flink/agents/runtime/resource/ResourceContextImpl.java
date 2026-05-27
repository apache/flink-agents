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

package org.apache.flink.agents.runtime.resource;

import org.apache.flink.agents.api.resource.Resource;
import org.apache.flink.agents.api.resource.ResourceContext;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.api.skills.Skills;
import org.apache.flink.agents.runtime.skill.SkillManager;

import javax.annotation.Nullable;

import java.util.Collections;
import java.util.List;
import java.util.function.BiFunction;

/**
 * Default {@link ResourceContext} implementation that delegates resource lookup to a {@link
 * BiFunction} (typically the underlying {@code ResourceCache::getResource}).
 *
 * <p>Mirrors the Python {@code flink_agents.runtime.resource_context.ResourceContextImpl}. The
 * skill methods lazily build a {@link SkillManager} from the {@code _skills_config} resource — if
 * no such resource is registered they return safe defaults (empty string / empty list).
 */
public class ResourceContextImpl implements ResourceContext, AutoCloseable {

    private final BiFunction<String, ResourceType, Resource> getResource;
    private final ClassLoader classLoader;

    @Nullable private SkillManager skillManager;
    private boolean skillManagerInitialized;

    /**
     * Construct a context that resolves {@code classpath:} skill sources via {@code classLoader}.
     * Production code passes the Flink user-code class loader (threaded through {@code
     * ResourceCache}); standalone use may use {@link #ResourceContextImpl(BiFunction)}.
     */
    public ResourceContextImpl(
            BiFunction<String, ResourceType, Resource> getResource, ClassLoader classLoader) {
        this.getResource = getResource;
        this.classLoader = classLoader;
    }

    /** Convenience overload that uses the current thread's context class loader. */
    public ResourceContextImpl(BiFunction<String, ResourceType, Resource> getResource) {
        this(getResource, Thread.currentThread().getContextClassLoader());
    }

    @Override
    public Resource getResource(String name, ResourceType type) throws Exception {
        try {
            return getResource.apply(name, type);
        } catch (RuntimeException e) {
            if (e.getCause() instanceof Exception) {
                throw (Exception) e.getCause();
            }
            throw e;
        }
    }

    @Override
    public String generateAvailableSkillsPrompt(List<String> skillNames) throws Exception {
        SkillManager manager = ensureSkillManager();
        return manager == null ? "" : manager.generateDiscoveryPrompt(skillNames);
    }

    @Override
    public List<String> getSkillDirs(List<String> skillNames) throws Exception {
        SkillManager manager = ensureSkillManager();
        return manager == null ? Collections.emptyList() : manager.getSkillDirs(skillNames);
    }

    /**
     * Returns the cached {@link SkillManager} for this context, or {@code null} if not configured.
     */
    @Nullable
    public synchronized SkillManager getSkillManager() throws Exception {
        return ensureSkillManager();
    }

    @Nullable
    private synchronized SkillManager ensureSkillManager() throws Exception {
        if (!skillManagerInitialized) {
            skillManagerInitialized = true;
            skillManager = createSkillManager();
        }
        return skillManager;
    }

    @Nullable
    private SkillManager createSkillManager() throws Exception {
        Skills config;
        try {
            Resource r = getResource(Skills.SKILLS_CONFIG, ResourceType.SKILLS);
            if (!(r instanceof Skills)) {
                return null;
            }
            config = (Skills) r;
        } catch (Exception e) {
            // No skills config registered — that's fine, return null.
            return null;
        }
        return new SkillManager(config, classLoader);
    }

    /**
     * Close the lazily-cached {@link SkillManager}, releasing every materialized temp directory
     * owned by its repositories. Idempotent. Called via {@code ResourceCache.close()} on operator
     * close, including during Flink failover when the JVM stays up.
     */
    @Override
    public synchronized void close() throws Exception {
        if (skillManager != null) {
            try {
                skillManager.close();
            } finally {
                skillManager = null;
                skillManagerInitialized = false;
            }
        }
    }
}
