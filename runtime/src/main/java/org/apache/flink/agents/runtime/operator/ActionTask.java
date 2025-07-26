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
import org.apache.flink.agents.plan.Action;
import org.apache.flink.agents.runtime.context.RunnerContextImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.apache.flink.util.Preconditions.checkNotNull;

/**
 * This class represents a task related to the execution of an action in {@link
 * ActionExecutionOperator}.
 *
 * <p>An action is split into multiple code blocks, and each code block is represented by an {@code
 * ActionTask}. You can call {@link #invoke()} to execute a code block, and retrieve the output
 * events using {@link #getOutputEvents()}. If the action contains additional code blocks, you can
 * obtain the next {@code ActionTask} via {@link #getGeneratedActionTask()} and continue executing
 * it.
 */
public abstract class ActionTask {

    protected static final Logger LOG = LoggerFactory.getLogger(ActionTask.class);

    protected final Object key;
    protected final Event event;
    protected final Action action;
    protected final RunnerContextImpl runnerContext;
    protected ActionTask generatedActionTask;

    public ActionTask(Object key, Event event, Action action, RunnerContextImpl runnerContext) {
        this.key = key;
        this.event = event;
        this.action = action;
        this.runnerContext = runnerContext;
    }

    public Object getKey() {
        return key;
    }

    public List<Event> getOutputEvents() {
        return runnerContext.drainEvents();
    }

    public ActionTask getGeneratedActionTask() {
        return checkNotNull(generatedActionTask, "The generated action task is null.");
    }

    /**
     * Invokes the action task.
     *
     * @return true if the action task is completed; false if the action task is not yet completed
     *     and a new action task has been generated to continue execution. The newly generated
     *     action task can be retrieved using {@link #getGeneratedActionTask()}.
     *     <p>Regardless of whether the action task is completed or not, you can retrieve the output
     *     event(s) * by calling {@link #getOutputEvents()}.
     */
    public abstract boolean invoke() throws Exception;
}
