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

import org.apache.flink.agents.api.InputEvent;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.plan.JavaFunction;
import org.apache.flink.agents.plan.actions.Action;

import java.util.List;

/**
 * Shared helpers for the manager contract tests in this package.
 *
 * <p>Provides a minimal {@link Action} backed by a no-op static Java function so individual tests
 * do not need to redeclare the boilerplate around {@link JavaFunction#JavaFunction(Class, String,
 * Class[])} signature checks.
 */
final class TestActions {

    private TestActions() {}

    /** Returns a minimal noop Java action backed by {@link #noop(InputEvent, RunnerContext)}. */
    static Action noopAction() {
        try {
            return new Action(
                    "noop",
                    new JavaFunction(
                            TestActions.class,
                            "noop",
                            new Class<?>[] {InputEvent.class, RunnerContext.class}),
                    List.of(InputEvent.class.getName()));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** No-op static method referenced by {@link #noopAction()}. Must be public for reflection. */
    public static void noop(InputEvent event, RunnerContext context) {}
}
