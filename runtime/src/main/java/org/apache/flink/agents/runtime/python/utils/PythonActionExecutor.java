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
import org.apache.flink.agents.runtime.python.event.PythonEvent;
import org.apache.flink.agents.runtime.utils.EventUtil;
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

    // ========== ASYNC THREAD POOL ===========
    private static final String CREATE_ASYNC_THREAD_POOL =
            "flink_runner_context.create_async_thread_pool";
    private static final String CLOSE_ASYNC_THREAD_POOL =
            "flink_runner_context.close_async_thread_pool";

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

    private final PythonInterpreter interpreter;
    private final String agentPlanJson;
    private Object pythonAsyncThreadPool;

    public PythonActionExecutor(PythonInterpreter interpreter, String agentPlanJson) {
        this.interpreter = interpreter;
        this.agentPlanJson = agentPlanJson;
    }

    public void open() throws Exception {
        interpreter.exec(PYTHON_IMPORTS);

        pythonAsyncThreadPool = interpreter.invoke(CREATE_ASYNC_THREAD_POOL);
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

        Object pythonRunnerContextObject =
                interpreter.invoke(
                        CREATE_FLINK_RUNNER_CONTEXT,
                        runnerContext,
                        agentPlanJson,
                        pythonAsyncThreadPool);

        Object pythonEventObject = interpreter.invoke(CONVERT_TO_PYTHON_OBJECT, event.getEvent());

        try {
            Object calledResult = function.call(pythonEventObject, pythonRunnerContextObject);
            if (calledResult == null) {
                return null;
            } else {
                // must be a generator
                String pythonGeneratorRef =
                        PYTHON_GENERATOR_VAR_NAME_PREFIX
                                + PYTHON_GENERATOR_VAR_ID.incrementAndGet();
                interpreter.set(pythonGeneratorRef, calledResult);
                return pythonGeneratorRef;
            }
        } catch (Exception e) {
            runnerContext.drainEvents(null);
            throw new PythonActionExecutionException("Failed to execute Python action", e);
        }
    }

    public PythonEvent wrapToInputEvent(Object eventData) {
        checkState(eventData instanceof byte[]);
        // wrap_to_input_event returns a tuple of (bytes, str)
        Object result = interpreter.invoke(WRAP_TO_INPUT_EVENT, eventData);
        checkState(result.getClass().isArray() && ((Object[]) result).length == 2);
        Object[] resultArray = (Object[]) result;
        byte[] eventBytes = (byte[]) resultArray[0];
        String eventString = (String) resultArray[1];
        return new PythonEvent(eventBytes, EventUtil.PYTHON_INPUT_EVENT_NAME, eventString);
    }

    public Object getOutputFromOutputEvent(byte[] pythonOutputEvent) {
        return interpreter.invoke(GET_OUTPUT_FROM_OUTPUT_EVENT, pythonOutputEvent);
    }

    /**
     * Invokes the next step of a Python generator.
     *
     * <p>This method is typically used after initializing or resuming a Python generator that was
     * created via a user-defined action involving asynchronous execution.
     *
     * @param pythonGeneratorRef the reference name of the Python generator object stored in the
     *     interpreter's context
     * @return true if the generator has completed; false otherwise
     */
    public boolean callPythonGenerator(String pythonGeneratorRef) {
        // Calling next(generator) in Python returns a tuple of (finished, output).
        Object pythonGenerator = interpreter.get(pythonGeneratorRef);
        Object invokeResult = interpreter.invoke(CALL_PYTHON_GENERATOR, pythonGenerator);
        checkState(invokeResult.getClass().isArray() && ((Object[]) invokeResult).length == 2);
        return (boolean) ((Object[]) invokeResult)[0];
    }

    public void close() throws Exception {
        if (interpreter != null) {
            if (pythonAsyncThreadPool != null) {
                interpreter.invoke(CLOSE_ASYNC_THREAD_POOL, pythonAsyncThreadPool);
            }
        }
    }

    /** Failed to execute Python action. */
    public static class PythonActionExecutionException extends Exception {
        public PythonActionExecutionException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
