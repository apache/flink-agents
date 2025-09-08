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

import javax.annotation.Nullable;

import java.util.List;
import java.util.Optional;

/**
 * This class represents a task related to the execution of an action in {@link
 * ActionExecutionOperator}.
 *
 * <p>An action is split into multiple code blocks, and each code block is represented by an {@code
 * ActionTask}. You can call {@link #invoke()} to execute a code block and obtain invoke result
 * {@link ActionTaskResult}. If the action contains additional code blocks, you can obtain the next
 * {@code ActionTask} via {@link ActionTaskResult#getGeneratedActionTask()} and continue executing
 * it.
 */
public abstract class ActionTask {

    protected static final Logger LOG = LoggerFactory.getLogger(ActionTask.class);

    protected final Object key;
    protected final Event event;
    protected final Action action;
    /**
     * Since RunnerContextImpl contains references to the Operator and state, it should not be
     * serialized and included in the state with ActionTask. Instead, we should check if a valid
     * RunnerContext exists before each ActionTask invocation and create a new one if necessary.
     */
    protected transient RunnerContextImpl runnerContext;

    public ActionTask(Object key, Event event, Action action) {
        this.key = key;
        this.event = event;
        this.action = action;
    }

    public RunnerContextImpl getRunnerContext() {
        return runnerContext;
    }

    public void setRunnerContext(RunnerContextImpl runnerContext) {
        this.runnerContext = runnerContext;
    }

    public Object getKey() {
        return key;
    }

    /** Invokes the action task. */
    public abstract ActionTaskResult invoke() throws Exception;

    public class ActionTaskResult {
        private final boolean finished;
        private final List<Event> outputEvents;
        private final Optional<ActionTask> generatedActionTaskOpt;

        public ActionTaskResult(
                boolean finished,
                List<Event> outputEvents,
                @Nullable ActionTask generatedActionTask) {
            this.finished = finished;
            this.outputEvents = outputEvents;
            this.generatedActionTaskOpt = Optional.ofNullable(generatedActionTask);
        }

        public boolean isFinished() {
            return finished;
        }

        public List<Event> getOutputEvents() {
            return outputEvents;
        }

        public Optional<ActionTask> getGeneratedActionTask() {
            return generatedActionTaskOpt;
        }

        @Override
        public String toString() {
            return "ActionTaskResult{"
                    + "finished="
                    + finished
                    + ", outputEvents="
                    + outputEvents
                    + ", generatedActionTaskOpt="
                    + generatedActionTaskOpt
                    + '}';
        }
    }
}
