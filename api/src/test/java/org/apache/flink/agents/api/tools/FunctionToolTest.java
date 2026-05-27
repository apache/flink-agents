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

package org.apache.flink.agents.api.tools;

import org.apache.flink.agents.api.function.JavaFunction;
import org.apache.flink.agents.api.function.PythonFunction;
import org.apache.flink.agents.api.resource.ResourceType;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

class FunctionToolTest {

    public static int demo(int a) {
        return a;
    }

    @Test
    void holdsPythonFunction() {
        PythonFunction pf = new PythonFunction("pkg.mod", "fn");
        FunctionTool tool = new FunctionTool(pf);
        assertThat(tool.getFunc()).isSameAs(pf);
        assertThat(tool.getResourceType()).isEqualTo(ResourceType.TOOL);
    }

    @Test
    void holdsJavaFunction() {
        JavaFunction jf = new JavaFunction("X", "m", java.util.List.of("int"));
        FunctionTool tool = new FunctionTool(jf);
        assertThat(tool.getFunc()).isSameAs(jf);
    }

    @Test
    void fromMethodBuildsJavaFunction() throws Exception {
        Method m = FunctionToolTest.class.getDeclaredMethod("demo", int.class);
        FunctionTool tool = FunctionTool.fromMethod(m);
        assertThat(tool.getFunc()).isInstanceOf(JavaFunction.class);
        JavaFunction jf = (JavaFunction) tool.getFunc();
        assertThat(jf.getQualName()).isEqualTo(FunctionToolTest.class.getName());
        assertThat(jf.getMethodName()).isEqualTo("demo");
        assertThat(jf.getParameterTypes()).containsExactly("int");
    }
}
