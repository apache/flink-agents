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
import org.apache.flink.agents.plan.actions.Action;
import org.apache.flink.agents.runtime.operator.ActionTask;
import org.apache.flink.agents.runtime.python.context.PythonRunnerContextImpl;
import org.apache.flink.agents.runtime.python.utils.PythonActionExecutor;

/** An {@link ActionTask} wrapper a Python awaitable to represent a code block in Python action. */
public class PythonGeneratorActionTask extends PythonActionTask {

    public PythonGeneratorActionTask(Object key, Event event, Action action) {
        super(key, event, action);
    }

    @Override
    public ActionTaskResult invoke(ClassLoader userCodeClassLoader, PythonActionExecutor executor)
            throws Exception {
        LOG.debug(
                "Try execute python awaitable action {} for event {} with key {}.",
                action.getName(),
                event,
                key);

        String pythonAwaitableRef =
                ((PythonRunnerContextImpl) runnerContext).getPythonAwaitableRef();

        if (pythonAwaitableRef == null) {
            LOG.info(
                    "Python awaitable ref is null for action {} (likely restored from checkpoint), "
                            + "re-executing from beginning.",
                    action.getName());
            PythonActionTask freshTask = new PythonActionTask(key, event, action);
            freshTask.setRunnerContext(runnerContext);
            return freshTask.invoke(userCodeClassLoader, executor);
        }

        boolean finished = executor.callPythonAwaitable(pythonAwaitableRef);
        ActionTask generatedActionTask = finished ? null : this;
        return new ActionTaskResult(
                finished,
                runnerContext.drainEvents(event.getSourceTimestamp()),
                generatedActionTask);
    }
}
