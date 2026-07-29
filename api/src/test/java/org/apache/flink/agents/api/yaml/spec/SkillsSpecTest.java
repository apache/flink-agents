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

package org.apache.flink.agents.api.yaml.spec;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SkillsSpecTest {
    private static final ObjectMapper M = new ObjectMapper(new YAMLFactory());

    @Test
    void parsesNameAndPaths() throws Exception {
        SkillsSpec spec = M.readValue("name: s\npaths: [./a, ./b]\n", SkillsSpec.class);
        assertThat(spec.getName()).isEqualTo("s");
        assertThat(spec.getPaths()).containsExactly("./a", "./b");
        assertThat(spec.getUrls()).isEmpty();
        assertThat(spec.getClasspath()).isEmpty();
    }

    @Test
    void parsesUrlsAndClasspath() throws Exception {
        SkillsSpec spec =
                M.readValue(
                        "name: s\nurls: [https://x/skills.zip]\nclasspath: [com/example/s]\n",
                        SkillsSpec.class);
        assertThat(spec.getPaths()).isEmpty();
        assertThat(spec.getUrls()).containsExactly("https://x/skills.zip");
        assertThat(spec.getClasspath()).containsExactly("com/example/s");
    }

    @Test
    void rejectsUnknownProperty() {
        assertThatThrownBy(() -> M.readValue("name: s\npaths: [./a]\nextra: 1\n", SkillsSpec.class))
                .hasMessageContaining("extra");
    }

    @Test
    void rejectsAllEmpty() {
        assertThatThrownBy(() -> M.readValue("name: s\n", SkillsSpec.class))
                .hasMessageContaining("at least one of paths/urls/classpath");
    }
}
