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

import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.plan.PythonFunction;
import org.apache.flink.agents.runtime.env.EmbeddedPythonEnvironment;
import org.apache.flink.agents.runtime.env.PythonEnvironmentManager;
import org.apache.flink.agents.runtime.memory.MemoryObjectImpl;
import org.apache.flink.agents.runtime.python.context.PythonRunnerContextImpl;
import org.apache.flink.agents.runtime.python.event.PythonEvent;
import org.apache.flink.agents.runtime.utils.EventUtil;
import org.apache.flink.api.common.state.MapState;
import pemja.core.PythonInterpreter;

import java.util.List;

import static org.apache.flink.util.Preconditions.checkState;

/** Execute the corresponding Python action in the agent. */
public class PythonActionExecutor {

    private static final String PYTHON_IMPORTS =
            "from flink_agents.plan import function\n"
                    + "from flink_agents.runtime import flink_runner_context\n"
                    + "from flink_agents.runtime import python_java_utils";
    private static final String CREATE_FLINK_RUNNER_CONTEXT =
            "flink_runner_context.create_flink_runner_context";
    private static final String CONVERT_TO_PYTHON_OBJECT =
            "python_java_utils.convert_to_python_object";
    private static final String WRAP_TO_INPUT_EVENT = "python_java_utils.wrap_to_input_event";
    private static final String GET_OUTPUT_FROM_OUTPUT_EVENT =
            "python_java_utils.get_output_from_output_event";
    private static final String FLINK_RUNNER_CONTEXT_VAR_NAME = "flink_runner_context";

    private final PythonEnvironmentManager environmentManager;
    private final String agentPlanJson;
    private PythonRunnerContextImpl runnerContext;

    private PythonInterpreter interpreter;

    public PythonActionExecutor(PythonEnvironmentManager environmentManager, String agentPlanJson) {
        this.environmentManager = environmentManager;
        this.agentPlanJson = agentPlanJson;
    }

    public void open(MapState<String, MemoryObjectImpl.MemoryItem> shortTermMemState)
            throws Exception {
        environmentManager.open();
        EmbeddedPythonEnvironment env = environmentManager.createEnvironment();

        interpreter = env.getInterpreter();
        interpreter.exec(PYTHON_IMPORTS);

        runnerContext = new PythonRunnerContextImpl(shortTermMemState);

        // TODO: remove the set and get runner context after updating pemja to version 0.5.3
        Object pythonRunnerContextObject =
                interpreter.invoke(CREATE_FLINK_RUNNER_CONTEXT, runnerContext, agentPlanJson);
        interpreter.set(FLINK_RUNNER_CONTEXT_VAR_NAME, pythonRunnerContextObject);
    }

    public List<Event> executePythonFunction(PythonFunction function, PythonEvent event)
            throws Exception {
        runnerContext.checkNoPendingEvents();
        function.setInterpreter(interpreter);

        // TODO: remove the set and get runner context after updating pemja to version 0.5.3
        Object pythonRunnerContextObject = interpreter.get(FLINK_RUNNER_CONTEXT_VAR_NAME);

        Object pythonEventObject = interpreter.invoke(CONVERT_TO_PYTHON_OBJECT, event.getEvent());

        try {
            function.call(pythonEventObject, pythonRunnerContextObject);
        } catch (Exception e) {
            runnerContext.drainEvents();
            throw new PythonActionExecutionException("Failed to execute Python action", e);
        }

        return runnerContext.drainEvents();
    }

    public PythonEvent wrapToInputEvent(Object eventData) {
        checkState(eventData instanceof byte[]);
        return new PythonEvent(
                (byte[]) interpreter.invoke(WRAP_TO_INPUT_EVENT, eventData),
                EventUtil.PYTHON_INPUT_EVENT_NAME);
    }

    public Object getOutputFromOutputEvent(byte[] pythonOutputEvent) {
        return interpreter.invoke(GET_OUTPUT_FROM_OUTPUT_EVENT, pythonOutputEvent);
    }

    /** Failed to execute Python action. */
    public static class PythonActionExecutionException extends Exception {
        public PythonActionExecutionException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
