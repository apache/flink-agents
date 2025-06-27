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
import pemja.core.PythonInterpreter;

import java.util.List;

/** Execute the corresponding Python action in the workflow. */
public class PythonActionExecutor {

    private static final String IMPORT_FLINK_RUNNER_CONTEXT =
            "from flink_agents.runtime import flink_runner_context";

    private final PythonEnvironmentManager environmentManager;
    private final PythonRunnerContext runnerContext;

    private PythonInterpreter interpreter;

    public PythonActionExecutor(PythonEnvironmentManager environmentManager) {
        this.environmentManager = environmentManager;
        this.runnerContext = new PythonRunnerContext();
    }

    public void open() throws Exception {
        environmentManager.open();
        EmbeddedPythonEnvironment env =
                (EmbeddedPythonEnvironment) environmentManager.createEnvironment();

        interpreter = env.getInterpreter();
        interpreter.exec(IMPORT_FLINK_RUNNER_CONTEXT);

        Object pythonFunctionWrapper =
                interpreter.invoke(
                        "flink_runner_context.create_python_function_wrapper", runnerContext);
        interpreter.set("python_function_wrapper", pythonFunctionWrapper);
    }

    public List<PythonEvent> executePythonFunction(
            PythonFunction pythonFunction, PythonEvent inputMessage) {
        runnerContext.checkNoPendingEvents();
        pythonFunction.setInterpreter(interpreter);

        try {
            pythonFunction.call(inputMessage.getEvent());
        } catch (Exception e) {
            runnerContext.drainEvents();
            throw new RuntimeException("Failed to execute the Python function", e);
        }

        return runnerContext.drainEvents();
    }
}
