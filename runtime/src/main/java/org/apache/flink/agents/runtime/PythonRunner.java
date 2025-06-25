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
import org.apache.flink.agents.runtime.message.DataMessage;
import pemja.core.PythonInterpreter;

import java.util.List;

/** Execute the corresponding Python action in the workflow. */
public class PythonRunner {
    private PythonInterpreter interpreter;
    private PythonRunnerContext runnerContext;

    public PythonRunner(PythonEnvironmentManager environmentManager) throws Exception {
        environmentManager.open();
        EmbeddedPythonEnvironment env = (EmbeddedPythonEnvironment) environmentManager.createEnvironment();

        interpreter = env.getInterpreter();
        interpreter.exec("from flink_agents.runtime import flink_runner_context");
    }

    public List executePythonFunction(PythonFunction pythonFunction, DataMessage<?> inputMessage) {
        int moduleLastDotIndex = pythonFunction.getModule().lastIndexOf(".");
        String packagePath = pythonFunction.getModule().substring(0, moduleLastDotIndex);
        String className = pythonFunction.getModule().substring(moduleLastDotIndex + 1);

        interpreter.exec("from " + packagePath + " import " + className);

        Object eventKey = inputMessage.getKey();
        runnerContext.setKey(eventKey);

        Object pythonRunnerContextObject =
                interpreter.invoke("flink_runner_context.get_runner_context", runnerContext);

        Object pythonEventObject =
                interpreter.invoke(
                        "flink_runner_context.get_python_object", inputMessage.getPayload());

        interpreter.invoke(pythonFunction.getQualName(), pythonEventObject, pythonRunnerContextObject);

        return runnerContext.getAllEvents();
    }
}
