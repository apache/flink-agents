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
package org.apache.flink.agents.runtime.operator;

import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.plan.JavaFunction;
import org.apache.flink.agents.plan.actions.Action;
import org.apache.flink.agents.runtime.context.JavaRunnerContextImpl;
import org.apache.flink.agents.runtime.python.utils.PythonActionExecutor;

import java.util.Collections;

import static org.apache.flink.util.Preconditions.checkState;

/**
 * A special {@link ActionTask} designed to execute a Java action task.
 *
 * <p>On JDK 21+, this task supports asynchronous execution via {@code executeAsync} in the action
 * code. When the action yields for async execution, this task returns with {@code finished=false}
 * and generates itself as the next task to continue execution.
 *
 * <p>On JDK &lt; 21, async execution falls back to synchronous mode.
 */
public class JavaActionTask extends ActionTask {

    private boolean executionStarted = false;

    public JavaActionTask(Object key, Event event, Action action) {
        super(key, event, action);
        checkState(action.getExec() instanceof JavaFunction);
    }

    @Override
    public ActionTaskResult invoke(ClassLoader userCodeClassLoader, PythonActionExecutor executor)
            throws Exception {
        LOG.debug(
                "Try execute java action {} for event {} with key {}.",
                action.getName(),
                event,
                key);

        if (!executionStarted) {
            runnerContext.checkNoPendingEvents();
            executionStarted = true;
        }

        JavaRunnerContextImpl javaRunnerContext = (JavaRunnerContextImpl) runnerContext;

        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        boolean finished;
        try {
            Thread.currentThread().setContextClassLoader(userCodeClassLoader);
            finished =
                    javaRunnerContext
                            .getContinuationExecutor()
                            .executeAction(
                                    javaRunnerContext.getContinuationContext(),
                                    () -> {
                                        try {
                                            action.getExec().call(event, runnerContext);
                                        } catch (Exception e) {
                                            throw new RuntimeException(e);
                                        }
                                    });
        } finally {
            Thread.currentThread().setContextClassLoader(cl);
        }

        if (finished) {
            return new ActionTaskResult(
                    true, runnerContext.drainEvents(event.getSourceTimestamp()), null);
        } else {
            return new ActionTaskResult(false, Collections.emptyList(), this);
        }
    }
}
