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
package org.apache.flink.agents.runtime;

import org.apache.flink.agents.plan.PythonFunction;
import org.apache.flink.agents.runtime.context.PythonRunnerContext;
import org.apache.flink.agents.runtime.env.EmbeddedPythonEnvironment;
import org.apache.flink.agents.runtime.env.PythonEnvironmentManager;
import org.apache.flink.agents.runtime.message.PythonEventMessage;
import pemja.core.PythonInterpreter;

import java.util.List;

/** Execute the corresponding Python action in the workflow. */
public class PythonRunner {

    private static final String IMPORT_FLINK_RUNNER_CONTEXT =
            "from flink_agents.runtime import flink_runner_context";
    private static final String CREATE_FLINK_RUNNER_CONTEXT =
            "flink_runner_context.create_flink_runner_context";
    private static final String CONVERT_TO_PYTHON_OBJECT =
            "flink_runner_context.convert_to_python_object";

    private PythonInterpreter interpreter;
    private PythonRunnerContext runnerContext;

    public PythonRunner(PythonEnvironmentManager environmentManager) throws Exception {
        environmentManager.open();
        EmbeddedPythonEnvironment env =
                (EmbeddedPythonEnvironment) environmentManager.createEnvironment();

        interpreter = env.getInterpreter();
        interpreter.exec(IMPORT_FLINK_RUNNER_CONTEXT);
    }

    public List executePythonFunction(
            PythonFunction pythonFunction, PythonEventMessage<?> inputMessage) {
        String modulePath = pythonFunction.getModule();
        int moduleLastDotIndex = modulePath.lastIndexOf(".");
        if (moduleLastDotIndex == -1) {
            throw new IllegalArgumentException(
                    "Invalid Python module path: " + pythonFunction.getModule());
        }

        String packagePath = modulePath.substring(0, moduleLastDotIndex);
        String className = modulePath.substring(moduleLastDotIndex + 1);

        Object eventKey = inputMessage.getKey();
        runnerContext.setKey(eventKey);
        runnerContext.clearAllEvents();

        try {
            interpreter.exec("from " + packagePath + " import " + className);

            Object pythonRunnerContextObject =
                    interpreter.invoke(CREATE_FLINK_RUNNER_CONTEXT, runnerContext);

            Object pythonEventObject =
                    interpreter.invoke(CONVERT_TO_PYTHON_OBJECT, inputMessage.getEvent());

            interpreter.invoke(
                    pythonFunction.getQualName(), pythonEventObject, pythonRunnerContextObject);
        } catch (Exception e) {
            throw new RuntimeException(
                    "Failed to execute Python function: " + pythonFunction.getQualName(), e);
        }

        return runnerContext.drainEvents();
    }
}
