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

package org.apache.flink.agents.api.resource;

import org.apache.flink.agents.api.skills.SkillSourceSpec;
import org.apache.flink.agents.api.tools.Tool;

import java.util.Collections;
import java.util.List;

/**
 * Capabilities available to a {@link Resource} during execution.
 *
 * <p>Mirrors the Python {@code flink_agents.api.resource_context.ResourceContext}.
 */
public interface ResourceContext {

    /** Get another resource declared in the same Agent. */
    Resource getResource(String name, ResourceType type) throws Exception;

    /**
     * Get an executable tool view of a subagent declared in this resource scope.
     *
     * @param subagentName the AGENT resource name, without the callable prefix
     * @throws UnsupportedOperationException if this context does not support subagent tools
     */
    Tool getSubagentTool(String subagentName) throws Exception;

    /**
     * Generate the available skills prompt for the given skill names.
     *
     * <p>Returns an empty string if no skills are configured.
     */
    String generateAvailableSkillsPrompt(List<String> skillNames) throws Exception;

    /**
     * Return absolute directory paths for the given skill names.
     *
     * <p>Returns an empty list if no skills are configured or none of the requested skills are
     * filesystem-backed.
     */
    List<String> getSkillDirs(List<String> skillNames) throws Exception;

    /**
     * Resolve skill names from the given source specifications.
     *
     * <p>At plan time, a descriptor may record a set of {@link SkillSourceSpec} entries as a marker
     * meaning "all skills from these sources." At runtime, this method resolves the marker into
     * actual skill names by looking up the sources in the agent's {@code SkillManager}.
     *
     * <p>Returns an empty list if no skills are configured or the sources are not loaded.
     */
    default List<String> resolveSkillNamesForSources(List<SkillSourceSpec> sources)
            throws Exception {
        return Collections.emptyList();
    }
}
