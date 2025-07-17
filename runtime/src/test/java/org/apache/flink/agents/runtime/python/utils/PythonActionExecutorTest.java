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
package org.apache.flink.agents.runtime.python.utils;

import org.apache.flink.agents.plan.PythonFunction;
import org.apache.flink.agents.runtime.env.PythonEnvironmentManager;
import org.apache.flink.api.common.JobID;
import org.apache.flink.python.env.PythonDependencyInfo;
import org.junit.jupiter.api.Test;

import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

public class PythonActionExecutorTest {

    private PythonEnvironmentManager createTestEnvironmentManager() {
        PythonDependencyInfo dependencyInfo =
                new PythonDependencyInfo(new HashMap<>(), null, null, new HashMap<>(), "python");
        return new PythonEnvironmentManager(
                dependencyInfo, new String[0], new HashMap<>(), new JobID());
    }

    @Test
    void testPythonFunctionCaching() {
        PythonEnvironmentManager environmentManager = createTestEnvironmentManager();
        PythonActionExecutor executor = new PythonActionExecutor(environmentManager);

        String module = "test_module";
        String qualName = "test_function";

        PythonFunction function1 = executor.getOrCreatePythonFunction(module, qualName);
        PythonFunction function2 = executor.getOrCreatePythonFunction(module, qualName);

        assertSame(function1, function2);
        assertEquals(module, function1.getModule());
        assertEquals(qualName, function1.getQualName());
    }

    @Test
    void testDifferentPythonFunctionsAreDistinct() {
        PythonEnvironmentManager environmentManager = createTestEnvironmentManager();
        PythonActionExecutor executor = new PythonActionExecutor(environmentManager);

        PythonFunction function1 = executor.getOrCreatePythonFunction("module1", "func1");
        PythonFunction function2 = executor.getOrCreatePythonFunction("module2", "func2");
        PythonFunction function3 = executor.getOrCreatePythonFunction("module1", "func2");

        assertEquals("module1", function1.getModule());
        assertEquals("func1", function1.getQualName());
        assertEquals("module2", function2.getModule());
        assertEquals("func2", function2.getQualName());
        assertEquals("module1", function3.getModule());
        assertEquals("func2", function3.getQualName());

        assertSame(function1, executor.getOrCreatePythonFunction("module1", "func1"));
        assertSame(function2, executor.getOrCreatePythonFunction("module2", "func2"));
        assertSame(function3, executor.getOrCreatePythonFunction("module1", "func2"));
    }
}
