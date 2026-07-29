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

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DescriptorSpecTest {
    private static final ObjectMapper M = new ObjectMapper(new YAMLFactory());

    @Test
    void requiresNameAndClazz() {
        assertThatThrownBy(() -> M.readValue("clazz: x.Y\n", DescriptorSpec.class))
                .hasMessageContaining("name");
        assertThatThrownBy(() -> M.readValue("name: n\n", DescriptorSpec.class))
                .hasMessageContaining("clazz");
    }

    @Test
    void passesExtrasThrough() throws Exception {
        DescriptorSpec spec =
                M.readValue(
                        "name: n\nclazz: x.Y\nbase_url: http://x\ntimeout: 5\n",
                        DescriptorSpec.class);
        assertThat(spec.getName()).isEqualTo("n");
        assertThat(spec.getClazz()).isEqualTo("x.Y");
        Map<String, Object> extras = spec.getExtras();
        assertThat(extras).containsEntry("base_url", "http://x").containsEntry("timeout", 5);
    }

    @Test
    void typeAcceptsPythonAndJava() throws Exception {
        DescriptorSpec py = M.readValue("name: n\nclazz: x\ntype: python\n", DescriptorSpec.class);
        DescriptorSpec ja = M.readValue("name: n\nclazz: x\ntype: java\n", DescriptorSpec.class);
        assertThat(py.getType()).isEqualTo(Language.PYTHON);
        assertThat(ja.getType()).isEqualTo(Language.JAVA);
    }

    @Test
    void typeOptional() throws Exception {
        DescriptorSpec spec = M.readValue("name: n\nclazz: x\n", DescriptorSpec.class);
        assertThat(spec.getType()).isNull();
    }
}
