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

import javax.annotation.Nullable;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Source of skills. Mirrors the Python {@code
 * flink_agents.runtime.skill.skill_repository.SkillRepository}.
 *
 * <p>Implementations that own a materialized temp directory (URL / classpath / zip) should override
 * {@link #close()} to release it eagerly; otherwise the JVM shutdown hook registered by {@code
 * SkillMaterializer} is the fallback.
 */
public interface SkillRepository extends AutoCloseable {

    /** Return the named skill, or {@code null} if not found. */
    @Nullable
    AgentSkill getSkill(String name);

    /** Return all skills in the repository (order is implementation-specific). */
    List<AgentSkill> getSkills();

    /**
     * Return the resource map for the named skill — keys are relative paths from the skill root,
     * values are the file contents.
     */
    Map<String, String> getResources(String name);

    /**
     * Return the absolute on-disk directory backing the named skill, or {@code null} if this
     * repository is not filesystem-backed. Filesystem-backed implementations should return {@code
     * baseDir.resolve(name)} regardless of whether {@code name} actually exists — callers verify
     * existence themselves where needed.
     */
    @Nullable
    default Path getSkillDir(String name) {
        return null;
    }

    /** Default no-op for repositories that don't own a temp directory. */
    @Override
    default void close() {}
}
