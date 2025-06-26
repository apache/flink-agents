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

import org.apache.flink.agents.runtime.message.EventMessage;
import org.apache.flink.agents.runtime.message.PythonEventMessage;

import javax.annotation.concurrent.NotThreadSafe;

import java.util.ArrayList;
import java.util.List;

/**
 * This class is used to manage the execution context when interacting with Python functions,
 * including temporarily saving the key of the current Event and collecting new events generated
 * during the execution of actions.
 */
@NotThreadSafe
public class PythonRunnerContext {
    private Object key;
    private List<EventMessage<?, ?>> events;

    public PythonRunnerContext() {
        this(null);
    }

    public PythonRunnerContext(Object key) {
        this.key = key;
        this.events = new ArrayList<>();
    }

    public void setKey(Object key) {
        this.key = key;
    }

    public void sendEvent(String type, byte[] event) {
        if (key == null) {
            throw new IllegalStateException("Key is not set.");
        }
        this.events.add(new PythonEventMessage<>(key, event, type));
    }

    public List<EventMessage<?, ?>> drainEvents() {
        List<EventMessage<?, ?>> list = new ArrayList<>(this.events);
        clearAllEvents();
        return list;
    }

    public void clearAllEvents() {
        this.events.clear();
    }
}
