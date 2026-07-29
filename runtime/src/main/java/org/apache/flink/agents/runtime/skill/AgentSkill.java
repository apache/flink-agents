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

package org.apache.flink.agents.runtime.skill;

import org.apache.flink.util.Preconditions;

import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Runtime representation of one parsed {@code SKILL.md}.
 *
 * <p>Mirrors the Python {@code flink_agents.runtime.skill.agent_skill.AgentSkill}. Resources are
 * lazily loaded on first access.
 */
public final class AgentSkill {

    private final String name;
    private final String description;
    private final String content;
    @Nullable private final String license;
    @Nullable private final String compatibility;
    @Nullable private final Map<String, String> metadata;
    @Nullable private volatile Map<String, String> resources;
    @Nullable private Supplier<Map<String, String>> resourceLoader;
    private volatile boolean activated;
    @Nullable private volatile SkillOrigin origin;

    public AgentSkill(
            String name,
            String description,
            String content,
            @Nullable String license,
            @Nullable String compatibility,
            @Nullable Map<String, String> metadata) {
        this(name, description, content, license, compatibility, metadata, null);
    }

    public AgentSkill(
            String name,
            String description,
            String content,
            @Nullable String license,
            @Nullable String compatibility,
            @Nullable Map<String, String> metadata,
            @Nullable Map<String, String> resources) {
        Preconditions.checkArgument(
                name != null && !name.isEmpty() && name.length() <= 64,
                "Skill name must be 1..64 characters: %s",
                name);
        Preconditions.checkArgument(
                description != null && !description.isEmpty() && description.length() <= 1024,
                "Skill description must be 1..1024 characters");
        Preconditions.checkArgument(
                content != null && !content.isEmpty(), "Skill content must not be empty");
        Preconditions.checkArgument(
                compatibility == null || compatibility.length() <= 500,
                "Skill compatibility must be at most 500 characters");
        this.name = name;
        this.description = description;
        this.content = content;
        this.license = license;
        this.compatibility = compatibility;
        this.metadata = metadata;
        this.resources = resources;
        this.activated = resources != null;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public String getContent() {
        return content;
    }

    @Nullable
    public String getLicense() {
        return license;
    }

    @Nullable
    public String getCompatibility() {
        return compatibility;
    }

    @Nullable
    public Map<String, String> getMetadata() {
        return metadata;
    }

    /** Set a lazy resource loader. Must be called before the first {@link #getResource(String)}. */
    public void setResourceLoader(Supplier<Map<String, String>> loader) {
        this.resourceLoader = loader;
    }

    /**
     * Set the origin (scheme + location) this skill was loaded from. Called by {@code
     * SkillManager.registerRepo} at registration time. Used for duplicate-name WARN and other
     * diagnostics.
     */
    public void setOrigin(SkillOrigin origin) {
        this.origin = origin;
    }

    @Nullable
    public SkillOrigin getOrigin() {
        return origin;
    }

    /**
     * Return the content of the named resource (relative path from the skill root) or {@code null}
     * if no such resource is registered.
     */
    @Nullable
    public String getResource(String relativePath) {
        activate();
        return resources == null ? null : resources.get(relativePath);
    }

    /** Return all registered resource relative paths (sorted, may be empty). */
    public List<String> getResourcePaths() {
        activate();
        if (resources == null) {
            return List.of();
        }
        List<String> keys = new ArrayList<>(resources.keySet());
        keys.sort(String::compareTo);
        return keys;
    }

    private synchronized void activate() {
        if (!activated && resourceLoader != null) {
            resources = resourceLoader.get();
            activated = true;
        }
    }
}
