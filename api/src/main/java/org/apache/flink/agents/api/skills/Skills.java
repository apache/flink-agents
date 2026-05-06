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

/**
 * Configuration resource describing where to load agent skills from.
 *
 * <p>Mirrors the Python {@code flink_agents.api.skills.Skills}. Use {@link
 * #fromLocalDir(String...)} to construct.
 *
 * <p>Multiple {@code @Skills} declarations on the same agent are merged at plan-build time.
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

    private List<String> paths;

    /** Required by Jackson. */
    public Skills() {
        this.paths = Collections.emptyList();
    }

    @JsonCreator
    public Skills(@JsonProperty("paths") List<String> paths) {
        this.paths = paths == null ? Collections.emptyList() : List.copyOf(paths);
    }

    /**
     * Create a {@link Skills} resource from one or more local filesystem directories.
     *
     * <p>Each path points to a directory whose immediate subdirectories each contain a {@code
     * SKILL.md} file.
     */
    public static Skills fromLocalDir(String... paths) {
        return new Skills(Arrays.asList(paths));
    }

    @JsonProperty("paths")
    public List<String> getPaths() {
        return paths;
    }

    @JsonIgnore
    @Override
    public ResourceType getResourceType() {
        return ResourceType.SKILLS;
    }
}
