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
package org.apache.flink.agents.api.function;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PythonFunctionTest {

    @Test
    void exposesModuleAndQualName() {
        PythonFunction fn = new PythonFunction("pkg.mod", "MyClass.method");
        assertThat(fn.getModule()).isEqualTo("pkg.mod");
        assertThat(fn.getQualName()).isEqualTo("MyClass.method");
    }

    @Test
    void equalsBasedOnModuleAndQualName() {
        PythonFunction a = new PythonFunction("m", "q");
        PythonFunction b = new PythonFunction("m", "q");
        PythonFunction c = new PythonFunction("m", "other");
        assertThat(a).isEqualTo(b).isNotEqualTo(c);
        assertThat(a).hasSameHashCodeAs(b);
    }

    @Test
    void jacksonRoundTrip() throws Exception {
        ObjectMapper m = new ObjectMapper();
        PythonFunction fn = new PythonFunction("pkg.mod", "fn");
        String json = m.writeValueAsString(fn);
        PythonFunction back = m.readValue(json, PythonFunction.class);
        assertThat(back).isEqualTo(fn);
    }
}
