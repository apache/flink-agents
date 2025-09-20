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
package org.apache.flink.agents.runtime.python.operator;

import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.plan.Action;
import org.apache.flink.agents.plan.PythonFunction;
import org.apache.flink.agents.runtime.operator.ActionTask;
import org.apache.flink.agents.runtime.python.event.PythonEvent;
import org.apache.flink.agents.runtime.python.utils.PythonActionExecutor;

import static org.apache.flink.util.Preconditions.checkState;

/**
 * A special {@link ActionTask} designed to execute a Python action task.
 *
 * <p>During asynchronous execution in Python, the {@link PythonActionTask} can produce a {@link
 * PythonGeneratorActionTask} to represent the subsequent code block when needed.
 */
public class PythonActionTask extends ActionTask {

    protected final PythonActionExecutor pythonActionExecutor;

    public PythonActionTask(
            Object key, Event event, Action action, PythonActionExecutor pythonActionExecutor) {
        super(key, event, action);
        checkState(action.getExec() instanceof PythonFunction);
        checkState(
                event instanceof PythonEvent,
                "Python action only accept python event, but got " + event);
        this.pythonActionExecutor = pythonActionExecutor;
    }

    public ActionTaskResult invoke() throws Exception {
        LOG.debug(
                "Try execute python action {} for event {} with key {}.",
                action.getName(),
                event,
                key);
        runnerContext.checkNoPendingEvents();

        String pythonGeneratorRef =
                pythonActionExecutor.executePythonFunction(
                        (PythonFunction) action.getExec(), (PythonEvent) event, runnerContext);
        // If a user-defined action uses an interface to submit asynchronous tasks, it will return a
        // Python generator object instance upon its first execution. Otherwise, it means that no
        // asynchronous tasks were submitted and the action has already completed.
        if (pythonGeneratorRef != null) {
            // The Python action generates a generator. We need to execute it once, which will
            // submit an asynchronous task and return whether the action has been completed.
            ActionTask tempGeneratedActionTask =
                    new PythonGeneratorActionTask(
                            key, event, action, pythonActionExecutor, pythonGeneratorRef);
            tempGeneratedActionTask.setRunnerContext(runnerContext);
            return tempGeneratedActionTask.invoke();
        }
        if (event.hasTimestamp()) {
            return new ActionTaskResult(
                    true, runnerContext.drainEvents(event.getTimestamp()), null);
        }
        return new ActionTaskResult(true, runnerContext.drainEvents(), null);
    }
}
