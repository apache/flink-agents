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

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillSourceRegistryTest {

    @Test
    void builtinSchemesAreRegistered() {
        assertNotNull(SkillSourceRegistry.get("local"));
        assertNotNull(SkillSourceRegistry.get("url"));
        assertNotNull(SkillSourceRegistry.get("classpath"));
    }

    @Test
    void getIsCaseInsensitive() {
        assertNotNull(SkillSourceRegistry.get("LOCAL"));
        assertNotNull(SkillSourceRegistry.get("ClassPath"));
    }

    @Test
    void unknownSchemeThrowsListingRegisteredSchemes() {
        IllegalArgumentException ex =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> SkillSourceRegistry.get("future-scheme"));
        assertTrue(ex.getMessage().contains("future-scheme"));
        assertTrue(ex.getMessage().contains("local"));
        assertTrue(ex.getMessage().contains("url"));
        assertTrue(ex.getMessage().contains("classpath"));
    }

    @Test
    void packageSchemeIsNotRegisteredOnJava() {
        // Python registers "package"; Java does not. Cross-language plans with scheme=package
        // fail loud at load time with the registered-scheme list.
        IllegalArgumentException ex =
                assertThrows(
                        IllegalArgumentException.class, () -> SkillSourceRegistry.get("package"));
        assertTrue(ex.getMessage().contains("package"));
    }

    @Test
    void customSchemeCanBeRegistered() throws Exception {
        SkillSourceRegistry.register(
                "fake-scheme-for-test",
                (params, cl) ->
                        new SkillRepository() {
                            @Override
                            public org.apache.flink.agents.runtime.skill.AgentSkill getSkill(
                                    String name) {
                                return null;
                            }

                            @Override
                            public java.util.List<org.apache.flink.agents.runtime.skill.AgentSkill>
                                    getSkills() {
                                return java.util.List.of();
                            }

                            @Override
                            public Map<String, String> getResources(String name) {
                                return Map.of();
                            }
                        });
        assertNotNull(SkillSourceRegistry.get("fake-scheme-for-test"));
        // Idempotent re-registration:
        SkillSourceRegistry.register("fake-scheme-for-test", (params, cl) -> null);
        assertNotNull(SkillSourceRegistry.get("fake-scheme-for-test"));
    }
}
