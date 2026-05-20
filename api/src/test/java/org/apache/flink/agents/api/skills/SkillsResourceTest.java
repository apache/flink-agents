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

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.agents.api.resource.ResourceType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SkillsResourceTest {

    @Test
    void fromLocalDirEmitsLocalScheme() {
        Skills skills = Skills.fromLocalDir("/tmp/a", "/tmp/b");
        assertEquals(
                List.of(
                        new SkillSourceSpec("local", Map.of("path", "/tmp/a")),
                        new SkillSourceSpec("local", Map.of("path", "/tmp/b"))),
                skills.getSources());
        assertEquals(ResourceType.SKILLS, skills.getResourceType());
    }

    @Test
    void fromUrlEmitsUrlScheme() {
        Skills skills = Skills.fromUrl("https://example.com/x.zip");
        assertEquals(
                List.of(new SkillSourceSpec("url", Map.of("url", "https://example.com/x.zip"))),
                skills.getSources());
    }

    @Test
    void fromClasspathEmitsClasspathScheme() {
        Skills skills = Skills.fromClasspath("skills");
        assertEquals(
                List.of(new SkillSourceSpec("classpath", Map.of("resource", "skills"))),
                skills.getSources());
    }

    @Test
    void roundTripsThroughJackson() throws Exception {
        Skills original = Skills.fromLocalDir("/tmp/skill1", "/tmp/skill2");
        ObjectMapper mapper = new ObjectMapper();
        String json = mapper.writeValueAsString(original);
        Skills restored = mapper.readValue(json, Skills.class);
        assertEquals(original.getSources(), restored.getSources());
    }

    @Test
    void reservedNamesMatchPython() {
        assertEquals("_skills_config", Skills.SKILLS_CONFIG);
        assertEquals("load_skill", Skills.LOAD_SKILL_TOOL);
        assertEquals("bash", Skills.BASH_TOOL);
    }
}
