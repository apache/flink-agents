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

package org.apache.flink.agents.plan.subagent;

import org.apache.flink.agents.api.agents.Agent;
import org.apache.flink.agents.plan.AgentPlan;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Thread-local cycle detection and plan reuse for sub-agent compilation.
 *
 * <p>Completed plans are memoized per {@link Agent} instance, so the same instance registered under
 * several names (or reachable through several parents) shares one compiled plan. Cycles are
 * rejected consistently — whether or not they run through the root agent — by tracking the agents
 * currently being compiled; encountering one again reports the resource-name path of the cycle. The
 * root caller owns the state: {@link #begin} returns {@code true} for it and it must call {@link
 * #end} in a finally block.
 */
public final class InternalSubagentCompilationHelper {

    /** Memoized plans, the agents currently being compiled, and the resource-name path. */
    private static final class State {
        private final Map<Agent, AgentPlan> compiled = new IdentityHashMap<>();
        private final Map<Agent, String> compiling = new IdentityHashMap<>();
        private final Deque<String> path = new ArrayDeque<>();
    }

    private static final ThreadLocal<State> STATE = new ThreadLocal<>();

    private InternalSubagentCompilationHelper() {}

    /**
     * Marks the start of a compilation walk. Call once from the root {@code AgentPlan} constructor;
     * the root is registered as being compiled, so a cycle running through it is detected like any
     * other.
     *
     * @return {@code true} if this call created the state (i.e. this is the root); the caller must
     *     call {@link #end()} in its {@code finally} block.
     */
    public static boolean begin(Agent root) {
        boolean owner = STATE.get() == null;
        if (owner) {
            STATE.set(new State());
            // Nested plans call begin too; only the owner registers the root (a cycle through it
            // must be reported like any other).
            STATE.get().compiling.put(root, "<root>");
            STATE.get().path.addLast("<root>");
        }
        return owner;
    }

    /** Cleans up the thread-local state. Only call when {@link #begin} returned true. */
    public static void end() {
        STATE.remove();
    }

    /**
     * Returns the previously compiled plan for {@code child}, compiling it on first encounter.
     *
     * @param child the agent to resolve a plan for.
     * @param name the resource name {@code child} is registered under; used for the cycle report.
     * @param compiler compiles the agent on first encounter.
     * @throws IllegalStateException when {@code child} is already being compiled higher up the
     *     stack, reporting the resource-name path of the cycle.
     */
    public static AgentPlan getOrCompile(Agent child, String name, Compiler compiler)
            throws Exception {
        State state = STATE.get();
        AgentPlan compiled = state.compiled.get(child);
        if (compiled != null) {
            return compiled;
        }
        if (state.compiling.containsKey(child)) {
            throw new IllegalStateException(
                    "Cyclic sub-agent definition detected: " + cyclePath(state, child, name));
        }
        state.compiling.put(child, name);
        state.path.addLast(name);
        try {
            AgentPlan plan = compiler.compile(child);
            state.compiled.put(child, plan);
            return plan;
        } finally {
            state.compiling.remove(child);
            state.path.removeLast();
        }
    }

    /** The resource-name path from the first occurrence of {@code child} back to itself. */
    private static String cyclePath(State state, Agent child, String name) {
        List<String> names = new ArrayList<>(state.path);
        int start = 0;
        for (int i = 0; i < names.size(); i++) {
            if (names.get(i).equals(state.compiling.get(child))) {
                start = i;
                break;
            }
        }
        StringBuilder builder = new StringBuilder();
        for (int i = start; i < names.size(); i++) {
            if (builder.length() > 0) {
                builder.append(" -> ");
            }
            builder.append(names.get(i));
        }
        builder.append(" -> ").append(name);
        return builder.toString();
    }

    /** Compilation callback that may throw checked exceptions. */
    @FunctionalInterface
    public interface Compiler {
        AgentPlan compile(Agent agent) throws Exception;
    }
}
