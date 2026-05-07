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

package org.apache.flink.agents.runtime.python.context;

import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.plan.AgentPlan;
import org.apache.flink.agents.runtime.ResourceCache;
import org.apache.flink.agents.runtime.context.RunnerContextImpl;
import org.apache.flink.agents.runtime.metrics.FlinkAgentsMetricGroupImpl;

import javax.annotation.concurrent.NotThreadSafe;

import java.io.IOException;

/** A specialized {@link RunnerContext} that is specifically used when executing Python actions. */
@NotThreadSafe
public class PythonRunnerContextImpl extends RunnerContextImpl {

    /**
     * Reference to the Python awaitable object in the interpreter. This is set when a Python action
     * yields an awaitable and is used by PythonGeneratorActionTask to resume execution.
     */
    private String pythonAwaitableRef;

    public PythonRunnerContextImpl(
            FlinkAgentsMetricGroupImpl agentMetricGroup,
            Runnable mailboxThreadChecker,
            AgentPlan agentPlan,
            ResourceCache resourceCache,
            String jobIdentifier) {
        super(agentMetricGroup, mailboxThreadChecker, agentPlan, resourceCache, jobIdentifier);
    }

    /**
     * Sends an event from Python to Java.
     *
     * <p>All Python events are serialized as JSON. The event is deserialized into a Java {@link
     * Event} and dispatched normally.
     *
     * @param eventJson JSON string with at least a {@code "type"} field
     * @throws IOException if JSON parsing fails
     */
    public void sendEventJson(String eventJson) throws IOException {
        Event event = Event.fromJson(eventJson);
        sendEvent(event);
    }

    public void checkMailboxThread() {
        // this method will be invoked by PythonActionExecutor's python interpreter.
        this.mailboxThreadChecker.run();
    }

    public String getPythonAwaitableRef() {
        return pythonAwaitableRef;
    }

    public void setPythonAwaitableRef(String pythonAwaitableRef) {
        this.pythonAwaitableRef = pythonAwaitableRef;
    }
}
