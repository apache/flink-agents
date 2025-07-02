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
package org.apache.flink.agents.runtime.context;

import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.runtime.PythonEvent;
import org.apache.flink.util.Preconditions;

import javax.annotation.concurrent.NotThreadSafe;

/**
 * This class is used to manage the execution context when interacting with Python functions,
 * including collecting new events generated during the execution of actions.
 */
@NotThreadSafe
public class PythonRunnerContextImpl extends RunnerContextImpl {

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
}
