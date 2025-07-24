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
import org.apache.flink.agents.api.context.MemoryObject;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.runtime.context.RunnerContextImpl;
import org.apache.flink.agents.runtime.memory.MemoryObjectImpl;
import org.apache.flink.agents.runtime.metrics.FlinkAgentsMetricGroupImpl;
import org.apache.flink.agents.runtime.python.event.PythonEvent;
import org.apache.flink.api.common.state.MapState;
import org.apache.flink.util.Preconditions;

import javax.annotation.concurrent.NotThreadSafe;

/** A specialized {@link RunnerContext} that is specifically used when executing Python actions. */
@NotThreadSafe
public class PythonRunnerContextImpl extends RunnerContextImpl {

    public PythonRunnerContextImpl(
            MapState<String, MemoryObjectImpl.MemoryItem> store,
            FlinkAgentsMetricGroupImpl agentMetricGroup) {
        super(store, agentMetricGroup);
    }

    @Override
    public void sendEvent(Event event) {
        Preconditions.checkState(
                event instanceof PythonEvent, "PythonRunnerContext only accept Python event.");
        super.sendEvent(event);
    }

    public void sendEvent(String type, byte[] event) {
        // this method will be invoked by PythonActionExecutor's python interpreter.
        sendEvent(new PythonEvent(event, type));
    }

    @Override
    public MemoryObject getShortTermMemory() throws Exception {
        return new MemoryObjectImpl(store, MemoryObjectImpl.ROOT_KEY);
    }
}
