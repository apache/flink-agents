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

import org.apache.flink.agents.api.skills.SkillSourceSpec;
import org.apache.flink.agents.api.skills.Skills;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Loads and indexes all skills referenced by a {@link Skills} configuration.
 *
 * <p>Mirrors the Python {@code flink_agents.runtime.skill.skill_manager.SkillManager}.
 *
 * <p>Owned by {@code ResourceContextImpl}; closed via {@code ResourceCache.close()} on operator
 * close, which cascades to each repository's temp directory. Avoid constructing a {@code
 * SkillManager} outside that flow without {@code try-with-resources}.
 */
public class SkillManager implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(SkillManager.class);

    private final Skills config;
    private final ClassLoader classLoader;
    private final Map<String, AgentSkill> skills = new LinkedHashMap<>();
    private final Map<String, SkillRepository> repos = new HashMap<>();

    /**
     * Every {@link SkillRepository} this manager has opened, in load order. Kept separately from
     * {@link #repos} because that map is keyed by skill name — when two sources register the same
     * skill name, the second {@code put} drops the first repo's reference. Close logic iterates
     * this list (dedup'd by identity) to avoid leaking those displaced repos.
     */
    private final List<SkillRepository> openedRepos = new ArrayList<>();

    /**
     * Construct a {@code SkillManager} that resolves {@code classpath:} sources against {@code
     * classLoader}. Production code passes the Flink user-code class loader (threaded through
     * {@code ResourceCache} from {@code ActionExecutionOperator}); tests / standalone use may use
     * {@link #SkillManager(Skills)}.
     */
    public SkillManager(Skills config, ClassLoader classLoader) {
        this.config = config;
        this.classLoader = classLoader;
        loadAll();
    }

    /**
     * Convenience overload that uses {@link Thread#getContextClassLoader} for {@code classpath:}
     * resolution. Prefer {@link #SkillManager(Skills, ClassLoader)} in production paths where the
     * Flink user-code class loader is available — the thread context loader is not guaranteed to be
     * the user-code loader (e.g. Python interpreter or async-pool threads).
     */
    public SkillManager(Skills config) {
        this(config, Thread.currentThread().getContextClassLoader());
    }

    public int size() {
        return skills.size();
    }

    public AgentSkill getSkill(String name) {
        AgentSkill skill = skills.get(name);
        if (skill == null) {
            throw new IllegalArgumentException(
                    "Skill "
                            + name
                            + " not found, available skill names are: "
                            + getAllSkillNames());
        }
        return skill;
    }

    public List<String> getAllSkillNames() {
        return new ArrayList<>(skills.keySet());
    }

    @Nullable
    public String loadSkillResource(String skillName, String resourcePath) {
        return getSkill(skillName).getResource(resourcePath);
    }

    /** Build the {@code <available_skills>} system prompt for the given skill names. */
    public String generateDiscoveryPrompt(List<String> names) {
        if (size() == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder(SkillPromptProvider.SKILL_DISCOVERY_PROMPT);
        for (String name : names) {
            AgentSkill skill = getSkill(name);
            sb.append(
                    String.format(
                            SkillPromptProvider.AVAILABLE_SKILL_TEMPLATE,
                            skill.getName(),
                            skill.getDescription()));
        }
        sb.append(SkillPromptProvider.AVAILABLE_SKILLS_TAG_END);
        return sb.toString();
    }

    /**
     * Absolute directory paths for the listed skill names (filesystem-backed only). When called
     * with an empty or {@code null} list, returns directories for all filesystem-backed skills.
     */
    public List<String> getSkillDirs(List<String> names) {
        Iterable<String> selected = (names == null || names.isEmpty()) ? repos.keySet() : names;
        List<String> dirs = new ArrayList<>();
        for (String skillName : selected) {
            SkillRepository repo = repos.get(skillName);
            if (repo == null) {
                throw new IllegalArgumentException(
                        "Skill "
                                + skillName
                                + " not found, available skill names are: "
                                + new ArrayList<>(repos.keySet()));
            }
            Path dir = repo.getSkillDir(skillName);
            if (dir != null) {
                dirs.add(dir.toString());
            }
        }
        return dirs;
    }

    /** Return absolute directory path for a single skill, if filesystem-backed. */
    @Nullable
    public Path getSkillDir(String skillName) {
        SkillRepository repo = repos.get(skillName);
        return repo == null ? null : repo.getSkillDir(skillName);
    }

    /** Resolve a skill resource's relative path to an absolute path, or {@code null} if missing. */
    @Nullable
    public Path resolveResourcePath(String skillName, String resourcePath) {
        SkillRepository repo = repos.get(skillName);
        if (repo == null) {
            return null;
        }
        Path dir = repo.getSkillDir(skillName);
        if (dir == null) {
            return null;
        }
        Path resolved = dir.resolve(resourcePath);
        return Files.isRegularFile(resolved) ? resolved : null;
    }

    private void loadAll() {
        for (SkillSourceSpec spec : config.getSources()) {
            try {
                SkillRepository repo =
                        SkillSourceRegistry.get(spec.getScheme())
                                .open(spec.getParams(), classLoader);
                openedRepos.add(repo);
                registerRepo(repo, originOf(spec));
            } catch (IOException | IllegalArgumentException e) {
                IllegalStateException toThrow =
                        new IllegalStateException(
                                "Failed to load skills from "
                                        + spec.getScheme()
                                        + ":"
                                        + spec.getParams(),
                                e);
                // Release repos registered before this point. The caller never receives a
                // SkillManager reference (we're throwing from the constructor path), so
                // without this cleanup their shutdown hooks + temp dirs would leak until
                // JVM exit.
                try {
                    closeRepos();
                } catch (Exception cleanupError) {
                    toThrow.addSuppressed(cleanupError);
                }
                throw toThrow;
            }
        }
    }

    /**
     * Build a {@link SkillOrigin} from a spec for diagnostics (WARN on duplicates, etc.). The
     * location description is delegated to the handler registered for the scheme — see {@link
     * SkillSourceHandler#describeLocation(Map)} — so adding a new scheme is a single registry call.
     */
    private static SkillOrigin originOf(SkillSourceSpec spec) {
        SkillSourceHandler handler = SkillSourceRegistry.get(spec.getScheme());
        return new SkillOrigin(spec.getScheme(), handler.describeLocation(spec.getParams()));
    }

    /**
     * Close every owned {@link SkillRepository}, releasing any temp directories materialized for
     * URL / classpath-zip / classpath-jar sources. Idempotent (delegated repos use {@link
     * java.util.concurrent.atomic.AtomicBoolean} guards).
     *
     * <p>Mirrors {@code ResourceCache.close()}: the first failure is rethrown after every repo has
     * been attempted, with subsequent failures attached as suppressed exceptions. This surfaces
     * real shutdown bugs (locked files, permission denied, disk full) instead of silently
     * swallowing them.
     */
    @Override
    public void close() throws Exception {
        closeRepos();
    }

    private void closeRepos() throws Exception {
        // Iterate openedRepos rather than repos.values(): duplicate skill names cause a later
        // registration to overwrite the earlier repo reference in `repos`, but the earlier repo
        // is still owned and must be closed. Dedup by identity in case the same repo
        // contributes multiple skills.
        Set<SkillRepository> unique = Collections.newSetFromMap(new IdentityHashMap<>());
        unique.addAll(openedRepos);
        Exception firstException = null;
        for (SkillRepository repo : unique) {
            try {
                repo.close();
            } catch (Exception e) {
                if (firstException == null) {
                    firstException = e;
                } else {
                    firstException.addSuppressed(e);
                }
            }
        }
        if (firstException != null) {
            throw firstException;
        }
    }

    private void registerRepo(SkillRepository repo, SkillOrigin origin) {
        for (AgentSkill skill : repo.getSkills()) {
            final String skillName = skill.getName();
            skill.setResourceLoader(() -> repo.getResources(skillName));
            skill.setOrigin(origin);
            AgentSkill previous = skills.put(skillName, skill);
            if (previous != null) {
                LOG.warn(
                        "Skill '{}' from {} overrides earlier registration from {}",
                        skillName,
                        origin,
                        previous.getOrigin() == null ? "<unknown>" : previous.getOrigin());
            }
            repos.put(skillName, repo);
        }
    }
}
