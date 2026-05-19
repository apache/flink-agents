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
import org.apache.flink.agents.api.yaml.Language;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToolSpecTest {
    private static final ObjectMapper M = new ObjectMapper(new YAMLFactory());

    @Test
    void minimalPythonTool() throws Exception {
        ToolSpec spec = M.readValue("name: t\nfunction: pkg:fn\n", ToolSpec.class);
        assertThat(spec.getName()).isEqualTo("t");
        assertThat(spec.getFunction()).isEqualTo("pkg:fn");
        assertThat(spec.getType()).isNull();
        assertThat(spec.getParameterTypes()).isNull();
    }

    @Test
    void javaToolWithParameterTypes() throws Exception {
        String yaml =
                "name: t\n"
                        + "function: com.example.X:m\n"
                        + "type: java\n"
                        + "parameter_types: [int, java.lang.String]\n";
        ToolSpec spec = M.readValue(yaml, ToolSpec.class);
        assertThat(spec.getType()).isEqualTo(Language.JAVA);
        assertThat(spec.getParameterTypes()).containsExactly("int", "java.lang.String");
    }

    @Test
    void rejectsUnknownProperty() {
        assertThatThrownBy(
                        () -> M.readValue("name: t\nfunction: pkg:fn\nextra: 1\n", ToolSpec.class))
                .hasMessageContaining("extra");
    }
}
