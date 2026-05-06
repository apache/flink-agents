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

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parser that splits a {@code SKILL.md} file into YAML frontmatter and markdown body, then
 * constructs an {@link AgentSkill}.
 *
 * <p>Mirrors the Python {@code flink_agents.runtime.skill.skill_parser}.
 */
public final class SkillParser {

    private static final Pattern FRONTMATTER =
            Pattern.compile(
                    "^---\\s*[\\r\\n]+(.*?)[\\r\\n]*---(?:\\s*[\\r\\n]+)?(.*)", Pattern.DOTALL);

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    private SkillParser() {}

    /** Result of splitting a markdown file. */
    public static final class ParsedMarkdown {
        public final Map<String, Object> metadata;
        public final String content;

        public ParsedMarkdown(Map<String, Object> metadata, String content) {
            this.metadata = metadata == null ? Collections.emptyMap() : metadata;
            this.content = content == null ? "" : content;
        }
    }

    /** Split the markdown into YAML frontmatter and the remaining body. */
    public static ParsedMarkdown parseMarkdown(String markdown) {
        if (markdown == null || markdown.isEmpty()) {
            return new ParsedMarkdown(Collections.emptyMap(), "");
        }
        Matcher m = FRONTMATTER.matcher(markdown);
        if (!m.matches()) {
            return new ParsedMarkdown(Collections.emptyMap(), markdown);
        }
        String yaml = m.group(1).trim();
        String body = m.group(2);
        if (yaml.isEmpty()) {
            return new ParsedMarkdown(Collections.emptyMap(), body);
        }
        try {
            Map<String, Object> metadata =
                    YAML.readValue(yaml, new TypeReference<Map<String, Object>>() {});
            return new ParsedMarkdown(metadata, body);
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "Invalid YAML frontmatter syntax: " + e.getMessage(), e);
        }
    }

    /** Parse a SKILL.md content into an {@link AgentSkill}. */
    public static AgentSkill parseSkill(String skillMdContent) {
        ParsedMarkdown parsed = parseMarkdown(skillMdContent);
        Map<String, Object> metadata = parsed.metadata;

        Object name = metadata.get("name");
        if (!(name instanceof String) || ((String) name).trim().isEmpty()) {
            throw new IllegalArgumentException(
                    "The SKILL.md must have a YAML frontmatter including 'name' field.");
        }
        Object description = metadata.get("description");
        if (!(description instanceof String) || ((String) description).trim().isEmpty()) {
            throw new IllegalArgumentException(
                    "The SKILL.md must have a YAML frontmatter including 'description' field.");
        }
        if (parsed.content == null || parsed.content.isEmpty()) {
            throw new IllegalArgumentException(
                    "The SKILL.md must have a markdown content after YAML frontmatter.");
        }

        Object license = metadata.get("license");
        Object compatibility = metadata.get("compatibility");
        Object inner = metadata.get("metadata");
        Map<String, String> innerMetadata = null;
        if (inner instanceof Map) {
            innerMetadata = new HashMap<>();
            for (Map.Entry<?, ?> e : ((Map<?, ?>) inner).entrySet()) {
                innerMetadata.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
            }
        }

        return new AgentSkill(
                ((String) name).trim(),
                ((String) description).trim(),
                parsed.content,
                license == null ? null : license.toString(),
                compatibility == null ? null : compatibility.toString(),
                innerMetadata);
    }
}
