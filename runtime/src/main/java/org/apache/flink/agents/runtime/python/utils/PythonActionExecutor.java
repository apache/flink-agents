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
import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.api.agents.AgentExecutionOptions;
import org.apache.flink.agents.api.resource.Resource;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.plan.AgentPlan;
import org.apache.flink.agents.plan.PythonFunction;
import org.apache.flink.agents.runtime.operator.ActionTask;
import org.apache.flink.agents.runtime.python.context.PythonRunnerContextImpl;
import org.apache.flink.agents.runtime.python.resource.PythonRuntimeResource;
import org.apache.flink.types.Row;
import org.apache.flink.util.ExceptionUtils;
import pemja.core.PythonInterpreter;
import pemja.core.object.PyObject;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.apache.flink.util.Preconditions.checkState;

/** Execute the corresponding Python action in the agent. */
public class PythonActionExecutor implements AutoCloseable {

    private static final String PYTHON_IMPORTS =
            "from flink_agents.plan import function\n"
                    + "from flink_agents.runtime import flink_runner_context\n"
                    + "from flink_agents.runtime import python_java_utils";

    // =========== RUNNER CONTEXT ===========
    private static final String CREATE_FLINK_RUNNER_CONTEXT =
            "flink_runner_context.create_flink_runner_context";

    private static final String CLOSE_FLINK_RUNNER_CONTEXT =
            "flink_runner_context.close_flink_runner_context";

    // =========== PYTHON RESOURCE MATERIALIZATION ===========
    private static final String EAGER_MATERIALIZE = "flink_runner_context.eager_materialize";

    // =========== TASK LIFECYCLE FORWARDING ===========
    private static final String ADD_TASK_LIFECYCLE_LISTENER =
            "flink_runner_context.add_task_lifecycle_listener";
    private static final String NOTIFY_RECORD_START = "flink_runner_context.notify_record_start";
    private static final String NOTIFY_ACTION_PREPARED =
            "flink_runner_context.notify_action_prepared";
    private static final String NOTIFY_ACTION_STARTED =
            "flink_runner_context.notify_action_started";
    private static final String NOTIFY_ACTION_TRANSFERRED =
            "flink_runner_context.notify_action_transferred";
    private static final String NOTIFY_ACTION_FINISHING =
            "flink_runner_context.notify_action_finishing";
    private static final String NOTIFY_ACTION_FINISHED =
            "flink_runner_context.notify_action_finished";
    private static final String NOTIFY_ACTION_REUSED = "flink_runner_context.notify_action_reused";
    private static final String NOTIFY_ACTION_FAILED = "flink_runner_context.notify_action_failed";
    private static final String NOTIFY_RECORD_FINISHED =
            "flink_runner_context.notify_record_finished";

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
    private static final String CONVERT_JSON_TO_PYTHON_EVENT =
            "python_java_utils.convert_json_to_python_event";
    private static final String CONVERT_TO_PYTHON_KEY_TEXT =
            "python_java_utils.convert_to_python_key_text";
    private static final String PICKLED_KEY_SERIALIZATION = "pickled";
    private static final String EXPLICIT_KEY_SERIALIZATION = "explicit";
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

    public PyObject getPythonRunnerContext() {
        return pythonRunnerContext;
    }

    /**
     * Materializes every resource of the given type that the Python runtime owns and returns one
     * handle per resource, keyed by resource name.
     *
     * <p>See {@link PythonRuntimeResource} for what the returned handle may and may not do.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Resource> eagerMaterialize(ResourceType type) {
        Object pythonResources =
                interpreter.invoke(EAGER_MATERIALIZE, pythonRunnerContext, type.getValue());
        if (pythonResources == null) {
            return Collections.emptyMap();
        }
        Map<String, Resource> handles = new HashMap<>();
        ((Map<String, PyObject>) pythonResources)
                .forEach(
                        (name, pythonResource) ->
                                handles.put(name, new PythonRuntimeResource(type, pythonResource)));
        return handles;
    }

    /**
     * Registers a Python object in the Python runtime's task lifecycle registry. The Python side
     * fans the operator's callbacks out to that registry when {@link
     * org.apache.flink.agents.runtime.lifecycle.PythonTaskLifecycleListener} forwards them.
     *
     * @return whether the object observes the lifecycle, so the caller can tell whether the Python
     *     runtime has anything to be notified about.
     */
    public boolean addTaskLifecycleListener(PyObject pythonListener) {
        Object registered =
                interpreter.invoke(
                        ADD_TASK_LIFECYCLE_LISTENER, pythonRunnerContext, pythonListener);
        return Boolean.TRUE.equals(registered);
    }

    /** Forwards {@code onRecordStart} to the Python runtime lifecycle listeners. */
    public void notifyRecordStart(Object key) {
        interpreter.invoke(NOTIFY_RECORD_START, pythonRunnerContext, key);
    }

    /** Forwards {@code onActionPrepared} to the Python runtime lifecycle listeners. */
    public void notifyActionPrepared(ActionTask task) {
        interpreter.invoke(NOTIFY_ACTION_PREPARED, pythonRunnerContext, task);
    }

    /** Forwards {@code onActionStarted} to the Python runtime lifecycle listeners. */
    public void notifyActionStarted(ActionTask task) {
        interpreter.invoke(NOTIFY_ACTION_STARTED, pythonRunnerContext, task);
    }

    /** Forwards {@code onActionTransferred} to the Python runtime lifecycle listeners. */
    public void notifyActionTransferred(ActionTask fromTask, ActionTask toTask) {
        interpreter.invoke(NOTIFY_ACTION_TRANSFERRED, pythonRunnerContext, fromTask, toTask);
    }

    /** Forwards {@code onActionFinishing} to the Python runtime lifecycle listeners. */
    public void notifyActionFinishing(ActionTask task) {
        interpreter.invoke(NOTIFY_ACTION_FINISHING, pythonRunnerContext, task);
    }

    /** Forwards {@code onActionFinished} to the Python runtime lifecycle listeners. */
    public void notifyActionFinished(ActionTask task) {
        interpreter.invoke(NOTIFY_ACTION_FINISHED, pythonRunnerContext, task);
    }

    /** Forwards {@code onActionReused} to the Python runtime lifecycle listeners. */
    public void notifyActionReused(ActionTask task) {
        interpreter.invoke(NOTIFY_ACTION_REUSED, pythonRunnerContext, task);
    }

    /** Forwards {@code onActionFailed} to the Python runtime lifecycle listeners. */
    public void notifyActionFailed(ActionTask task, Throwable error) {
        interpreter.invoke(NOTIFY_ACTION_FAILED, pythonRunnerContext, task, error);
    }

    /** Forwards {@code onRecordFinished} to the Python runtime lifecycle listeners. */
    public void notifyRecordFinished(Object key) {
        interpreter.invoke(NOTIFY_RECORD_FINISHED, pythonRunnerContext, key);
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
    public String executePythonFunction(PythonFunction function, Event event) throws Exception {
        runnerContext.checkNoPendingEvents();
        function.setInterpreter(interpreter);

        String eventJson = new ObjectMapper().writeValueAsString(event);
        Object pythonEventObject = interpreter.invoke(CONVERT_JSON_TO_PYTHON_EVENT, eventJson);

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

    public Event wrapToInputEvent(Object eventData) throws IOException {
        checkState(eventData instanceof byte[]);
        // wrap_to_input_event returns a JSON string
        Object result = interpreter.invoke(WRAP_TO_INPUT_EVENT, eventData);
        checkState(result instanceof String);
        return Event.fromJson((String) result);
    }

    /** Resolves the textual logical key from PyFlink's keyed-stream representation. */
    public String resolveKeyText(Object flinkKey, boolean pythonKeyIsPickled) {
        Object logicalKey = flinkKey;
        if (flinkKey instanceof Row) {
            logicalKey = ((Row) flinkKey).getField(0);
        }
        if (pythonKeyIsPickled || logicalKey instanceof byte[]) {
            String keySerialization =
                    pythonKeyIsPickled ? PICKLED_KEY_SERIALIZATION : EXPLICIT_KEY_SERIALIZATION;
            return (String)
                    interpreter.invoke(
                            CONVERT_TO_PYTHON_KEY_TEXT, (byte[]) logicalKey, keySerialization);
        }
        return String.valueOf(logicalKey);
    }

    public Object getOutputFromOutputEvent(String eventJson) {
        return interpreter.invoke(GET_OUTPUT_FROM_OUTPUT_EVENT, eventJson);
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

    @Override
    public void close() throws Exception {
        // The two Python-side cleanups are independent, so attempt both even when the first
        // fails. Skipping the runner-context cleanup leaves that context's long-term memory and
        // resource cache unreleased, and PythonBridgeManager closes the interpreter right behind
        // us, so there is no later chance to run it. The first failure is rethrown with the later
        // one suppressed, matching the ladders in the managers above.
        if (interpreter == null) {
            return;
        }

        // Clear the fields before releasing: PyObject.close() performs an unguarded native decRef,
        // so a repeated close() must not reach the same handle twice.
        PyObject asyncThreadPool = pythonAsyncThreadPool;
        PyObject runnerContext = pythonRunnerContext;
        pythonAsyncThreadPool = null;
        pythonRunnerContext = null;

        Throwable firstFailure = null;
        try {
            closePythonObject(CLOSE_ASYNC_THREAD_POOL, asyncThreadPool);
        } catch (Throwable t) {
            firstFailure = ExceptionUtils.firstOrSuppressed(t, firstFailure);
        }
        try {
            closePythonObject(CLOSE_FLINK_RUNNER_CONTEXT, runnerContext);
        } catch (Throwable t) {
            firstFailure = ExceptionUtils.firstOrSuppressed(t, firstFailure);
        }

        if (firstFailure != null) {
            ExceptionUtils.rethrowException(firstFailure);
        }
    }

    private void closePythonObject(String closeFunction, PyObject pythonObject) throws Exception {
        if (pythonObject != null) {
            try (pythonObject) {
                interpreter.invoke(closeFunction, pythonObject);
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
