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

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JavaFunctionTest {

    public static int add(int a, int b) {
        return a + b;
    }

    @Test
    void exposesAllFields() {
        JavaFunction fn =
                new JavaFunction("com.example.X", "add", List.of("int", "java.lang.String"));
        assertThat(fn.getQualName()).isEqualTo("com.example.X");
        assertThat(fn.getMethodName()).isEqualTo("add");
        assertThat(fn.getParameterTypes()).containsExactly("int", "java.lang.String");
    }

    @Test
    void fromMethodCapturesDeclaringClassAndPrimitiveAndReferenceParams() throws Exception {
        Method m = JavaFunctionTest.class.getDeclaredMethod("add", int.class, int.class);
        JavaFunction fn = JavaFunction.fromMethod(m);
        assertThat(fn.getQualName())
                .isEqualTo("org.apache.flink.agents.api.function.JavaFunctionTest");
        assertThat(fn.getMethodName()).isEqualTo("add");
        assertThat(fn.getParameterTypes()).containsExactly("int", "int");
    }

    @Test
    void parameterTypesListIsDefensiveCopy() {
        var src = new java.util.ArrayList<>(List.of("int"));
        JavaFunction fn = new JavaFunction("X", "m", src);
        src.add("mutated");
        assertThat(fn.getParameterTypes()).containsExactly("int");
    }

    @Test
    void equalsBasedOnAllFields() {
        JavaFunction a = new JavaFunction("X", "m", List.of("int"));
        JavaFunction b = new JavaFunction("X", "m", List.of("int"));
        JavaFunction c = new JavaFunction("X", "m", List.of("long"));
        assertThat(a).isEqualTo(b).isNotEqualTo(c);
        assertThat(a).hasSameHashCodeAs(b);
    }

    @Test
    void jacksonRoundTrip() throws Exception {
        ObjectMapper m = new ObjectMapper();
        JavaFunction fn = new JavaFunction("com.example.X", "m", List.of("int"));
        String json = m.writeValueAsString(fn);
        JavaFunction back = m.readValue(json, JavaFunction.class);
        assertThat(back).isEqualTo(fn);
    }
}
