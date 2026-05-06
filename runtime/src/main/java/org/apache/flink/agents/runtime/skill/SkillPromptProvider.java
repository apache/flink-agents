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

/**
 * System-prompt templates for skill discovery and activation.
 *
 * <p>Mirrors the Python {@code flink_agents.runtime.skill.skill_prompt_provider} byte-for-byte.
 * Descriptions injected into {@link #AVAILABLE_SKILL_TEMPLATE} must not contain {@code %} (no skill
 * fixture currently does — the SKILL.md schema bounds descriptions to plain text).
 */
public final class SkillPromptProvider {

    public static final String SKILL_DISCOVERY_PROMPT =
            "## Available Skills\n"
                    + "\n"
                    + "<usage>\n"
                    + "Skills provide specialized capabilities and domain knowledge. Use them when they match your current task.\n"
                    + "\n"
                    + "Load a skill with `load_skill(name=\"<skill-name>\")` to get its full instructions.\n"
                    + "Individual resources (scripts, references, assets) can be loaded with a `path` argument.\n"
                    + "\n"
                    + "The loaded content includes the skill's base directory and the absolute paths of its resources.\n"
                    + "</usage>\n"
                    + "\n"
                    + "<available_skills>\n";

    public static final String AVAILABLE_SKILL_TEMPLATE =
            "\n<skill>\n<name>%s</name>\n<description>%s</description>\n</skill>\n";

    public static final String AVAILABLE_SKILLS_TAG_END = "\n</available_skills>\n";

    private SkillPromptProvider() {}
}
