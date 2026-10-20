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

import org.apache.flink.agents.runtime.python.context.PythonRunnerContextImpl;
import org.junit.jupiter.api.Test;

import javax.annotation.Nullable;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The graph-call entry points the Python bridge reaches over pemja, pinned by name and arity.
 *
 * <p>The bridge looks these up by string and the Python tests hold a fake context, so a rename used
 * to fail only in a cross-language run.
 */
class PythonBridgeContractTest {

    /** The two Python modules that call into the runner context by name. */
    private static final Path RUNNER_CONTEXT_PY =
            Paths.get("..", "python", "flink_agents", "runtime", "flink_runner_context.py");

    private static final Path CHAT_FUTURE_PY =
            Paths.get("..", "python", "flink_agents", "runtime", "chat_future.py");

    private static final Pattern RUNNER_CONTEXT_CALL =
            Pattern.compile("_j_runner_context\\.([A-Za-z_][A-Za-z0-9_]*)");

    private static final Pattern CHAT_FUTURE_CALL =
            Pattern.compile("self\\._ctx\\.([A-Za-z_][A-Za-z0-9_]*)");

    /**
     * The internal sub-agent poll and the chat poll both report "still running" as a Java {@code
     * null}, which is what lets the bridge yield the mailbox instead of blocking it. Both return a
     * terminal outcome as JSON, the wire format the bridge converts.
     */
    @Test
    void graphCallEntryPointsKeepTheSignaturesTheBridgeCalls() throws Exception {
        assertThat(
                        method(
                                        "bootstrapInternalSubagentCallForScope",
                                        String.class,
                                        String.class,
                                        String.class,
                                        Object.class)
                                .getReturnType())
                .isEqualTo(void.class);

        Method subagentPoll = method("tryCompleteInternalSubagentCall", String.class, String.class);
        assertThat(subagentPoll.getReturnType()).isEqualTo(String.class);
        assertThat(subagentPoll.isAnnotationPresent(Nullable.class)).isTrue();

        assertThat(method("prepareChatCall", String.class).getReturnType()).isEqualTo(String.class);

        Method chatPoll = method("tryCompleteChatCall", String.class);
        assertThat(chatPoll.getReturnType()).isEqualTo(String.class);
        assertThat(chatPoll.isAnnotationPresent(Nullable.class)).isTrue();
    }

    /**
     * The chat call used to run through a {@code ChatContext} the bridge held; a returning accessor
     * would let that second path be rebuilt beside the graph-call chain.
     */
    @Test
    void removedChatAccessorsStayRemoved() {
        assertThat(methodNames(PythonRunnerContextImpl.class))
                .doesNotContain("getChatContext", "setChatContext", "awaitSubagentCall");
    }

    /**
     * Every name the two bridge modules look up resolves on the {@link PythonRunnerContextImpl} the
     * operator hands them, so a Java-side rename cannot leave a Python call site pointing at
     * nothing. It is a sibling of {@link JavaRunnerContextImpl}, not a subclass.
     */
    @Test
    void everyPythonCallSiteResolvesOnTheRunnerContext() throws IOException {
        Set<String> called = new TreeSet<>();
        called.addAll(callSites(RUNNER_CONTEXT_PY, RUNNER_CONTEXT_CALL));
        called.addAll(callSites(CHAT_FUTURE_PY, CHAT_FUTURE_CALL));

        assertThat(called)
                .contains(
                        "bootstrapInternalSubagentCallForScope",
                        "tryCompleteInternalSubagentCall",
                        "prepareChatCall",
                        "tryCompleteChatCall");
        assertThat(methodNames(PythonRunnerContextImpl.class)).containsAll(called);
    }

    private static Method method(String name, Class<?>... parameters) throws Exception {
        return RunnerContextImpl.class.getMethod(name, parameters);
    }

    private static Set<String> methodNames(Class<?> type) {
        return Arrays.stream(type.getMethods()).map(Method::getName).collect(Collectors.toSet());
    }

    private static Set<String> callSites(Path source, Pattern call) throws IOException {
        assertThat(source).exists();
        Set<String> names = new TreeSet<>();
        Matcher matcher =
                call.matcher(new String(Files.readAllBytes(source), StandardCharsets.UTF_8));
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return names;
    }
}
