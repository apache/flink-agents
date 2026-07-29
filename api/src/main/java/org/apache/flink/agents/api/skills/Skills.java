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

package org.apache.flink.agents.api.skills;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.api.resource.SerializableResource;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Configuration resource describing where to load agent skills from.
 *
 * <p>The single field {@code sources} holds an ordered list of {@link SkillSourceSpec} entries.
 * Each entry has a {@code scheme} (e.g. {@code "local"}, {@code "url"}, {@code "classpath"}, {@code
 * "package"}) and a scheme-specific {@code params} map. Use one of the factory methods to construct
 * a {@link Skills} resource:
 *
 * <ul>
 *   <li>{@link #fromLocalDir(String...)} for local directories or {@code .zip} files
 *   <li>{@link #fromUrl(String...)} for http(s) URLs pointing to a {@code .zip}
 *   <li>{@link #fromClasspath(String...)} for resources on the classpath
 * </ul>
 *
 * <p>The {@code "package"} scheme exists on the Python side only (Java has no analogous concept). A
 * plan written by Python with {@code scheme=package} deserializes successfully on Java, but {@code
 * SkillManager} will fail fast at load time with the registered-scheme list.
 *
 * <p>Multiple {@code @Skills} declarations on the same agent are merged at plan-build time;
 * duplicate {@link SkillSourceSpec} entries (same {@code scheme} and {@code params}) are collapsed.
 */
@JsonIgnoreProperties(
        ignoreUnknown = true,
        value = {"metricGroup", "resourceType"})
public class Skills extends SerializableResource {

    /** Reserved resource name under which AgentPlan registers the merged Skills config. */
    public static final String SKILLS_CONFIG = "_skills_config";

    /** Reserved name of the built-in skill loader tool. */
    public static final String LOAD_SKILL_TOOL = "load_skill";

    /** Reserved name of the built-in bash tool used to execute skill scripts. */
    public static final String BASH_TOOL = "bash";

    private final List<SkillSourceSpec> sources;

    /** Required by Jackson. */
    public Skills() {
        this.sources = Collections.emptyList();
    }

    @JsonCreator
    public Skills(@JsonProperty("sources") List<SkillSourceSpec> sources) {
        this.sources = sources == null ? Collections.emptyList() : List.copyOf(sources);
    }

    /**
     * Create a {@link Skills} resource from one or more local paths.
     *
     * <p>Each path may be a directory whose immediate subdirectories each contain a {@code
     * SKILL.md} file, or a {@code .zip} file whose top-level entries are the skill subdirectories.
     */
    public static Skills fromLocalDir(String... paths) {
        return new Skills(
                Arrays.stream(paths)
                        .map(p -> new SkillSourceSpec("local", Map.of("path", p)))
                        .collect(Collectors.toList()));
    }

    /**
     * Create a {@link Skills} resource from one or more http(s) URLs.
     *
     * <p>Each URL must point to a {@code .zip} whose top level is the baseDir.
     */
    public static Skills fromUrl(String... urls) {
        return new Skills(
                Arrays.stream(urls)
                        .map(u -> new SkillSourceSpec("url", Map.of("url", u)))
                        .collect(Collectors.toList()));
    }

    /**
     * Create a {@link Skills} resource from one or more classpath resource paths.
     *
     * <p>Each resource may be a directory (e.g. under {@code src/main/resources/skills}) or a
     * {@code .zip} file. When packaged into a JAR, the resource is loaded via the thread context
     * class loader and materialized to a temp directory at runtime.
     */
    public static Skills fromClasspath(String... resources) {
        return new Skills(
                Arrays.stream(resources)
                        .map(r -> new SkillSourceSpec("classpath", Map.of("resource", r)))
                        .collect(Collectors.toList()));
    }

    @JsonProperty("sources")
    public List<SkillSourceSpec> getSources() {
        return sources;
    }

    @JsonIgnore
    @Override
    public ResourceType getResourceType() {
        return ResourceType.SKILLS;
    }
}
