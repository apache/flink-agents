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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.agents.api.agents.AgentExecutionOptions;
import org.apache.flink.agents.plan.AgentPlan;
import org.apache.flink.agents.plan.PythonFunction;
import org.apache.flink.agents.runtime.python.context.PythonRunnerContextImpl;
import org.apache.flink.agents.runtime.python.event.PythonEvent;
import org.apache.flink.agents.runtime.utils.EventUtil;
import pemja.core.PythonInterpreter;
import pemja.core.object.PyObject;

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

    private static final String FLINK_RUNNER_CONTEXT_SWITCH_ACTION_CONTEXT =
            "flink_runner_context.flink_runner_context_switch_action_context";

    private static final String CLOSE_FLINK_RUNNER_CONTEXT =
            "flink_runner_context.close_flink_runner_context";

    // ========== ASYNC THREAD POOL ===========
    private static final String CREATE_ASYNC_THREAD_POOL =
            "flink_runner_context.create_async_thread_pool";
    private static final String CLOSE_ASYNC_THREAD_POOL =
            "flink_runner_context.close_async_thread_pool";

    // =========== PYTHON AWAITABLE ===========
    private static final String CALL_PYTHON_AWAITABLE = "function.call_python_awaitable";
    private static final String PYTHON_AWAITABLE_VAR_NAME_PREFIX = "python_awaitable_";
    private static final AtomicLong PYTHON_AWAITABLE_VAR_ID = new AtomicLong(0);

    // =========== PYTHON AND JAVA OBJECT CONVERT ===========
    private static final String CONVERT_TO_PYTHON_OBJECT =
            "python_java_utils.convert_to_python_object";
    private static final String WRAP_TO_INPUT_EVENT = "python_java_utils.wrap_to_input_event";
    private static final String GET_OUTPUT_FROM_OUTPUT_EVENT =
            "python_java_utils.get_output_from_output_event";

    private final PythonInterpreter interpreter;
    private final AgentPlan agentPlan;
    private final PythonRunnerContextImpl runnerContext;
    private final JavaResourceAdapter javaResourceAdapter;
    private final String jobIdentifier;
    private PyObject pythonAsyncThreadPool;
    private PyObject pythonRunnerContext;

    public PythonActionExecutor(
            PythonInterpreter interpreter,
            AgentPlan agentPlan,
            JavaResourceAdapter javaResourceAdapter,
            PythonRunnerContextImpl runnerContext,
            String jobIdentifier)
            throws JsonProcessingException {
        this.interpreter = interpreter;
        this.agentPlan = agentPlan;
        this.runnerContext = runnerContext;
        this.javaResourceAdapter = javaResourceAdapter;
        this.jobIdentifier = jobIdentifier;
    }

    public void open() throws Exception {
        interpreter.exec(PYTHON_IMPORTS);

        pythonAsyncThreadPool =
                (PyObject)
                        interpreter.invoke(
                                CREATE_ASYNC_THREAD_POOL,
                                agentPlan.getConfig().get(AgentExecutionOptions.NUM_ASYNC_THREADS));

        pythonRunnerContext =
                (PyObject)
                        interpreter.invoke(
                                CREATE_FLINK_RUNNER_CONTEXT,
                                runnerContext,
                                new ObjectMapper().writeValueAsString(agentPlan),
                                pythonAsyncThreadPool,
                                javaResourceAdapter,
                                jobIdentifier);
    }

    /**
     * Execute the Python function, which may return a Python coroutine (awaitable) that needs to be
     * processed in the future. Due to an issue in Pemja regarding incorrect object reference
     * counting, this may lead to garbage collection of the object. To prevent this, we use the set
     * and get methods to manually increment the object's reference count, then return the name of
     * the Python awaitable variable.
     *
     * @return The name of the Python awaitable variable. It may be null if the Python function does
     *     not return a coroutine.
     */
    public String executePythonFunction(PythonFunction function, PythonEvent event, int hashOfKey)
            throws Exception {
        runnerContext.checkNoPendingEvents();
        function.setInterpreter(interpreter);

        interpreter.invoke(
                FLINK_RUNNER_CONTEXT_SWITCH_ACTION_CONTEXT, pythonRunnerContext, hashOfKey);

        Object pythonEventObject = interpreter.invoke(CONVERT_TO_PYTHON_OBJECT, event.getEvent());

        try {
            Object calledResult = function.call(pythonEventObject, pythonRunnerContext);
            if (calledResult == null) {
                return null;
            } else {
                // must be a coroutine (awaitable)
                String pythonAwaitableRef =
                        PYTHON_AWAITABLE_VAR_NAME_PREFIX
                                + PYTHON_AWAITABLE_VAR_ID.incrementAndGet();
                interpreter.set(pythonAwaitableRef, calledResult);
                return pythonAwaitableRef;
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
        String eventJsonStr = (String) resultArray[1];
        return new PythonEvent(eventBytes, EventUtil.PYTHON_INPUT_EVENT_NAME, eventJsonStr);
    }

    public Object getOutputFromOutputEvent(byte[] pythonOutputEvent) {
        return interpreter.invoke(GET_OUTPUT_FROM_OUTPUT_EVENT, pythonOutputEvent);
    }

    /**
     * Invokes the next step of a Python awaitable (coroutine or generator).
     *
     * <p>This method is typically used after initializing or resuming a Python coroutine that was
     * created via a user-defined action involving asynchronous execution.
     *
     * @param pythonAwaitableRef the reference name of the Python awaitable object stored in the
     *     interpreter's context
     * @return true if the awaitable has completed; false otherwise
     */
    public boolean callPythonAwaitable(String pythonAwaitableRef) {
        // Calling awaitable.send(None) in Python returns a tuple of (finished, output).
        Object pythonAwaitable = interpreter.get(pythonAwaitableRef);
        checkState(
                pythonAwaitable != null,
                "Python awaitable '%s' not found in interpreter. ",
                pythonAwaitableRef);
        Object invokeResult = interpreter.invoke(CALL_PYTHON_AWAITABLE, pythonAwaitable);
        checkState(invokeResult.getClass().isArray() && ((Object[]) invokeResult).length == 2);
        return (boolean) ((Object[]) invokeResult)[0];
    }

    public void close() throws Exception {
        if (interpreter != null) {
            if (pythonAsyncThreadPool != null) {
                interpreter.invoke(CLOSE_ASYNC_THREAD_POOL, pythonAsyncThreadPool);
            }

            if (pythonRunnerContext != null) {
                try {
                    interpreter.invoke(CLOSE_FLINK_RUNNER_CONTEXT, pythonRunnerContext);
                } finally {
                    pythonRunnerContext = null;
                }
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
