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
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.util.Preconditions;

import java.util.ArrayList;
import java.util.List;

/**
 * The implementation class of {@link RunnerContext}, which serves as the execution context for
 * Java-based actions.
 */
public class RunnerContextImpl implements RunnerContext {

    protected final List<Event> events = new ArrayList<>();

    @Override
    public void sendEvent(Event event) {
        events.add(event);
    }

    public List<Event> drainEvents() {
        List<Event> list = new ArrayList<>(this.events);
        this.events.clear();
        return list;
    }

    public void checkNoPendingEvents() {
        Preconditions.checkState(
                this.events.isEmpty(), "There are pending events remaining in the context.");
    }
}
