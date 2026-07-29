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

package org.apache.flink.agents.api.yaml;

import org.apache.flink.agents.api.function.JavaFunction;
import org.apache.flink.agents.api.function.PythonFunction;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class YamlLoaderFunctionTest {

    @Test
    void pythonRef() {
        Object fn = YamlLoader.resolveFunction("a", "pkg.mod:fn", Language.PYTHON, null);
        assertThat(fn).isInstanceOf(PythonFunction.class);
        PythonFunction pf = (PythonFunction) fn;
        assertThat(pf.getModule()).isEqualTo("pkg.mod");
        assertThat(pf.getQualName()).isEqualTo("fn");
    }

    @Test
    void javaRef() {
        Object fn =
                YamlLoader.resolveFunction(
                        "a",
                        "com.example.X:m",
                        Language.JAVA,
                        List.of("org.apache.flink.agents.api.Event"));
        assertThat(fn).isInstanceOf(JavaFunction.class);
        JavaFunction jf = (JavaFunction) fn;
        assertThat(jf.getQualName()).isEqualTo("com.example.X");
        assertThat(jf.getMethodName()).isEqualTo("m");
        assertThat(jf.getParameterTypes()).containsExactly("org.apache.flink.agents.api.Event");
    }

    @Test
    void rejectsMissingFunction() {
        assertThatThrownBy(() -> YamlLoader.resolveFunction("a", null, Language.PYTHON, null))
                .hasMessageContaining("'function' is required");
    }

    @Test
    void rejectsMissingColon() {
        assertThatThrownBy(() -> YamlLoader.resolveFunction("a", "pkg.fn", Language.PYTHON, null))
                .hasMessageContaining("module-or-class");
    }

    @Test
    void rejectsMultipleColons() {
        assertThatThrownBy(() -> YamlLoader.resolveFunction("a", "a:b:c", Language.PYTHON, null))
                .hasMessageContaining("module-or-class");
    }

    @Test
    void rejectsEmptyLeftOrRight() {
        assertThatThrownBy(() -> YamlLoader.resolveFunction("a", ":fn", Language.PYTHON, null))
                .hasMessageContaining("module-or-class");
        assertThatThrownBy(() -> YamlLoader.resolveFunction("a", "pkg:", Language.PYTHON, null))
                .hasMessageContaining("module-or-class");
    }
}
