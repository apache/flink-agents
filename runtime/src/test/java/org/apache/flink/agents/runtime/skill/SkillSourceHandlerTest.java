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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillSourceHandlerTest {
    private final SkillManager manager =
            new SkillManager(new org.apache.flink.agents.api.skills.Skills(java.util.List.of()));

    @Test
    void builtinSchemesAreRegistered() {
        assertNotNull(manager.getHandler("local"));
        assertNotNull(manager.getHandler("url"));
        assertNotNull(manager.getHandler("classpath"));
    }

    @Test
    void getIsCaseInsensitive() {
        assertNotNull(manager.getHandler("LOCAL"));
        assertNotNull(manager.getHandler("ClassPath"));
    }

    @Test
    void unknownSchemeThrowsListingRegisteredSchemes() {
        IllegalArgumentException ex =
                assertThrows(
                        IllegalArgumentException.class, () -> manager.getHandler("future-scheme"));
        assertTrue(ex.getMessage().contains("future-scheme"));
        assertTrue(ex.getMessage().contains("local"));
        assertTrue(ex.getMessage().contains("url"));
        assertTrue(ex.getMessage().contains("classpath"));
    }

    @Test
    void packageSchemeRequiresRuntimeContext() {
        java.io.IOException ex =
                assertThrows(
                        java.io.IOException.class,
                        () ->
                                manager.getHandler("package")
                                        .open(
                                                Map.of("package", "demo", "resource", "skills"),
                                                getClass().getClassLoader()));
        assertTrue(ex.getMessage().contains("requires an initialized Python runtime bridge"));
    }

    @Test
    void urlLocationDescriptionOmitsCredentialsAndQuery() {
        String description =
                manager.getHandler("url")
                        .describeLocation(
                                Map.of(
                                        "url",
                                        "https://user:password@example.com/x.zip?token=secret#part"));

        assertEquals("https://example.com/x.zip", description);
    }

    @Test
    void handlersAreOwnedByEachManager() {
        SkillManager other =
                new SkillManager(
                        new org.apache.flink.agents.api.skills.Skills(java.util.List.of()));
        assertNotSame(manager.getHandler("package"), other.getHandler("package"));
    }
}
