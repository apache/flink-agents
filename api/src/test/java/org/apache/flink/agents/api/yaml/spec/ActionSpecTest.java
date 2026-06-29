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

class ActionSpecTest {
    private static final ObjectMapper M = new ObjectMapper(new YAMLFactory());

    @Test
    void minimal() throws Exception {
        ActionSpec spec =
                M.readValue(
                        "name: a\nfunction: pkg:fn\ntrigger_conditions: [input]\n",
                        ActionSpec.class);
        assertThat(spec.getName()).isEqualTo("a");
        assertThat(spec.getFunction()).isEqualTo("pkg:fn");
        assertThat(spec.getTriggerConditions()).containsExactly("input");
        assertThat(spec.getConfig()).isNull();
        assertThat(spec.getType()).isNull();
    }

    @Test
    void rejectsEmptyTriggerConditions() {
        assertThatThrownBy(
                        () ->
                                M.readValue(
                                        "name: a\nfunction: x:y\ntrigger_conditions: []\n",
                                        ActionSpec.class))
                .hasMessageContaining("trigger_conditions");
    }

    @Test
    void typeJava() throws Exception {
        ActionSpec spec =
                M.readValue(
                        "name: a\nfunction: X:m\ntrigger_conditions: [input]\ntype: java\n",
                        ActionSpec.class);
        assertThat(spec.getType()).isEqualTo(Language.JAVA);
    }

    @Test
    void rejectsUnknownProperty() {
        assertThatThrownBy(
                        () ->
                                M.readValue(
                                        "name: a\nfunction: x:y\ntrigger_conditions: [input]\nextra: 1\n",
                                        ActionSpec.class))
                .hasMessageContaining("extra");
    }
}
