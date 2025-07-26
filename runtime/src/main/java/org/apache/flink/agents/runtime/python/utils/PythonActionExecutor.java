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
import org.apache.flink.agents.runtime.context.RunnerContextImpl;
import org.apache.flink.agents.runtime.env.EmbeddedPythonEnvironment;
import org.apache.flink.agents.runtime.env.PythonEnvironmentManager;
import org.apache.flink.agents.runtime.python.event.PythonEvent;
import org.apache.flink.agents.runtime.utils.EventUtil;
import org.apache.flink.api.java.tuple.Tuple2;
import pemja.core.PythonInterpreter;

import java.util.concurrent.atomic.AtomicLong;

import static org.apache.flink.util.Preconditions.checkState;

/** Execute the corresponding Python action in the agent. */
public class PythonActionExecutor {

    private static final String PYTHON_IMPORTS =
            "from flink_agents.plan import function\n"
                    + "from flink_agents.runtime import flink_runner_context\n"
                    + "from flink_agents.runtime import python_java_utils";

    // =========== RUNNER CONTEXT ===========
    private static final String CREATE_FLINK_RUNNER_CONTEXT =
            "flink_runner_context.create_flink_runner_context";
    private static final String FLINK_RUNNER_CONTEXT_VAR_NAME_PREFIX = "flink_runner_context_";
    private static final AtomicLong FLINK_RUNNER_CONTEXT_VAR_ID = new AtomicLong(0);

    // ========== ASYNC THREAD POOL ===========
    private static final String CREATE_ASYNC_THREAD_POOL =
            "flink_runner_context.create_async_thread_pool";
    private static final String PYTHON_ASYNC_THREAD_POOL_VAR_NAME_PREFIX =
            "python_async_thread_pool";
    private static final AtomicLong PYTHON_ASYNC_THREAD_POOL_VAR_ID = new AtomicLong(0);

    // =========== PYTHON GENERATOR ===========
    private static final String CALL_PYTHON_GENERATOR = "function.call_python_generator";
    private static final String PYTHON_GENERATOR_VAR_NAME_PREFIX = "python_generator_";
    private static final AtomicLong PYTHON_GENERATOR_VAR_ID = new AtomicLong(0);

    // =========== PYTHON AND JAVA OBJECT CONVERT ===========
    private static final String CONVERT_TO_PYTHON_OBJECT =
            "python_java_utils.convert_to_python_object";
    private static final String WRAP_TO_INPUT_EVENT = "python_java_utils.wrap_to_input_event";
    private static final String GET_OUTPUT_FROM_OUTPUT_EVENT =
            "python_java_utils.get_output_from_output_event";

    private final PythonEnvironmentManager environmentManager;
    private final String agentPlanJson;
    private PythonInterpreter interpreter;
    private String pythonAsyncThreadPoolObjectName;

    public PythonActionExecutor(PythonEnvironmentManager environmentManager, String agentPlanJson) {
        this.environmentManager = environmentManager;
        this.agentPlanJson = agentPlanJson;
    }

    public void open() throws Exception {
        environmentManager.open();
        EmbeddedPythonEnvironment env = environmentManager.createEnvironment();

        interpreter = env.getInterpreter();
        interpreter.exec(PYTHON_IMPORTS);

        // TODO: remove the set and get thread pool after updating pemja to version 0.5.3
        Object pythonAsyncThreadPool = interpreter.invoke(CREATE_ASYNC_THREAD_POOL);
        this.pythonAsyncThreadPoolObjectName =
                PYTHON_ASYNC_THREAD_POOL_VAR_NAME_PREFIX
                        + PYTHON_ASYNC_THREAD_POOL_VAR_ID.incrementAndGet();
        interpreter.set(pythonAsyncThreadPoolObjectName, pythonAsyncThreadPool);
    }

    /**
     * Execute the Python function, which may return a Python generator that needs to be processed
     * in the future. Due to an issue in Pemja regarding incorrect object reference counting, this
     * may lead to garbage collection of the object. To prevent this, we use the set and get methods
     * to manually increment the object's reference count, then return the name of the Python
     * generator variable.
     *
     * @return The name of the Python generator variable. It may be null if the Python function does
     *     not return a generator.
     */
    public String executePythonFunction(
            PythonFunction function, PythonEvent event, RunnerContextImpl runnerContext)
            throws Exception {
        runnerContext.checkNoPendingEvents();
        function.setInterpreter(interpreter);

        // TODO: remove the set and get runner context after updating pemja to version 0.5.3
        Object pythonRunnerContextObject =
                interpreter.invoke(
                        CREATE_FLINK_RUNNER_CONTEXT,
                        runnerContext,
                        agentPlanJson,
                        interpreter.get(pythonAsyncThreadPoolObjectName));
        String pythonRunnerContextObjectName =
                FLINK_RUNNER_CONTEXT_VAR_NAME_PREFIX
                        + FLINK_RUNNER_CONTEXT_VAR_ID.incrementAndGet();
        interpreter.set(pythonRunnerContextObjectName, pythonRunnerContextObject);

        Object pythonEventObject = interpreter.invoke(CONVERT_TO_PYTHON_OBJECT, event.getEvent());

        try {
            Object calledResult = function.call(pythonEventObject, pythonRunnerContextObject);
            if (calledResult == null) {
                return null;
            } else {
                // must be a generator
                String pythonGeneratorName =
                        PYTHON_GENERATOR_VAR_NAME_PREFIX
                                + PYTHON_GENERATOR_VAR_ID.incrementAndGet();
                interpreter.set(pythonGeneratorName, calledResult);
                return pythonGeneratorName;
            }
        } catch (Exception e) {
            runnerContext.drainEvents();
            throw new PythonActionExecutionException("Failed to execute Python action", e);
        }
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

    public Tuple2<Boolean, Object> callPythonGenerator(String pythonGeneratorUUID) {
        // Calling next(generator) in Python returns a tuple of (finished, output).
        Object pythonGenerator = interpreter.get(pythonGeneratorUUID);
        Object invokeResult = interpreter.invoke(CALL_PYTHON_GENERATOR, pythonGenerator);
        checkState(invokeResult.getClass().isArray() && ((Object[]) invokeResult).length == 2);
        return Tuple2.of((boolean) ((Object[]) invokeResult)[0], ((Object[]) invokeResult)[1]);
    }

    /** Failed to execute Python action. */
    public static class PythonActionExecutionException extends Exception {
        public PythonActionExecutionException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
