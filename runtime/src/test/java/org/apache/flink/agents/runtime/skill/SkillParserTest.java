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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillParserTest {

    @Test
    void parseMarkdownSplitsFrontmatterFromBody() {
        String md =
                "---\n"
                        + "name: my-skill\n"
                        + "description: Does X\n"
                        + "---\n"
                        + "# Body\n"
                        + "Some markdown.\n";
        SkillParser.ParsedMarkdown parsed = SkillParser.parseMarkdown(md);
        assertEquals("my-skill", parsed.metadata.get("name"));
        assertEquals("Does X", parsed.metadata.get("description"));
        assertTrue(parsed.content.startsWith("# Body"));
    }

    @Test
    void parseMarkdownNoFrontmatterReturnsRawContent() {
        String md = "# Just a body\nNo frontmatter.";
        SkillParser.ParsedMarkdown parsed = SkillParser.parseMarkdown(md);
        assertTrue(parsed.metadata.isEmpty());
        assertEquals(md, parsed.content);
    }

    @Test
    void parseMarkdownHandlesCrlfLineEndings() {
        String md = "---\r\nname: x\r\ndescription: y\r\n---\r\nBody\r\n";
        SkillParser.ParsedMarkdown parsed = SkillParser.parseMarkdown(md);
        assertEquals("x", parsed.metadata.get("name"));
        assertTrue(parsed.content.contains("Body"));
    }

    @Test
    void parseSkillRequiresName() {
        String md = "---\ndescription: y\n---\nBody\n";
        IllegalArgumentException ex =
                assertThrows(IllegalArgumentException.class, () -> SkillParser.parseSkill(md));
        assertTrue(ex.getMessage().contains("name"));
    }

    @Test
    void parseSkillRequiresDescription() {
        String md = "---\nname: x\n---\nBody\n";
        IllegalArgumentException ex =
                assertThrows(IllegalArgumentException.class, () -> SkillParser.parseSkill(md));
        assertTrue(ex.getMessage().contains("description"));
    }

    @Test
    void parseSkillRequiresBody() {
        String md = "---\nname: x\ndescription: y\n---\n";
        IllegalArgumentException ex =
                assertThrows(IllegalArgumentException.class, () -> SkillParser.parseSkill(md));
        assertTrue(ex.getMessage().contains("markdown content"));
    }

    @Test
    void parseSkillSurfacesYamlSyntaxError() {
        String md = "---\nname: x\n  bad-indent: : :\n---\nBody\n";
        IllegalArgumentException ex =
                assertThrows(IllegalArgumentException.class, () -> SkillParser.parseSkill(md));
        assertTrue(ex.getMessage().startsWith("Invalid YAML frontmatter syntax"));
    }

    @Test
    void parseSkillTrimsNameAndDescription() {
        String md =
                "---\nname: \"  trimmed-name  \"\ndescription: \"  trimmed desc  \"\n---\nBody\n";
        AgentSkill skill = SkillParser.parseSkill(md);
        assertEquals("trimmed-name", skill.getName());
        assertEquals("trimmed desc", skill.getDescription());
    }

    @Test
    void parseSkillCarriesOptionalFields() {
        String md =
                "---\n"
                        + "name: test\n"
                        + "description: Does X\n"
                        + "license: Apache-2.0\n"
                        + "compatibility: macOS, Linux\n"
                        + "metadata:\n"
                        + "  author: alice\n"
                        + "  version: \"1.2\"\n"
                        + "---\n"
                        + "# Body\n";
        AgentSkill skill = SkillParser.parseSkill(md);
        assertEquals("Apache-2.0", skill.getLicense());
        assertEquals("macOS, Linux", skill.getCompatibility());
        assertNotNull(skill.getMetadata());
        assertEquals("alice", skill.getMetadata().get("author"));
        assertEquals("1.2", skill.getMetadata().get("version"));
    }
}
