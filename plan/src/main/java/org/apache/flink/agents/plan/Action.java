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

package org.apache.flink.agents.plan;

import org.apache.flink.agents.api.Event;

import java.util.List;

/**
 * Representation of a workflow action with event listening and function execution.
 *
 * <p>This class encapsulates a named workflow action that listens for specific event types and
 * executes an associated function when those events occur.
 */
public class Action {
    private final String name;
    private final Function exec;
    private final List<Class<? extends Event>> listenEventTypes;

    public Action(String name, Function exec, List<Class<? extends Event>> listenEventTypes)
            throws Exception {
        this.name = name;
        this.exec = exec;
        this.listenEventTypes = listenEventTypes;
        exec.checkSignature(new Class[] {Event.class});
    }

    public String getName() {
        return name;
    }

    public Function getExec() {
        return exec;
    }

    public List<Class<? extends Event>> getListenEventTypes() {
        return listenEventTypes;
    }
}
