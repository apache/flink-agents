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
import org.apache.flink.agents.plan.JavaFunction;

import static org.apache.flink.util.Preconditions.checkState;

/**
 * A special {@link ActionTask} designed to execute a Java action task.
 *
 * <p>Note that Java action currently do not support asynchronous execution. As a result, a Java
 * action task will be invoked only once.
 */
public class JavaActionTask extends ActionTask {

    public JavaActionTask(Object key, Event event, Action action) {
        super(key, event, action);
        checkState(action.getExec() instanceof JavaFunction);
    }

    @Override
    public ActionTaskResult invoke() throws Exception {
        LOG.debug(
                "Try execute java action {} for event {} with key {}.",
                action.getName(),
                event,
                key);
        runnerContext.checkNoPendingEvents();
        action.getExec().call(event, runnerContext);
        if (event.hasTimestamp()) {
            return new ActionTaskResult(
                    true, runnerContext.drainEvents(event.getTimestamp()), null);
        }
        return new ActionTaskResult(true, runnerContext.drainEvents(), null);
    }
}
