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
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class SkillOriginTest {

    @Test
    void toStringIsSchemeColonLocation() {
        SkillOrigin o = new SkillOrigin("url", "https://example.com/x.zip");
        assertEquals("url:https://example.com/x.zip", o.toString());
    }

    @Test
    void equalityBySchemeAndLocation() {
        SkillOrigin a = new SkillOrigin("local", "/data/skills");
        SkillOrigin b = new SkillOrigin("local", "/data/skills");
        SkillOrigin c = new SkillOrigin("local", "/data/other");
        SkillOrigin d = new SkillOrigin("url", "/data/skills");
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, c);
        assertNotEquals(a, d);
    }
}
