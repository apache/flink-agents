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

package org.apache.flink.agents.runtime.async;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Guards the test-classpath wiring that makes continuations available to tests.
 *
 * <p>The continuation-based executor is compiled into {@code META-INF/versions/21}, which only
 * takes effect for a packaged jar; tests run against {@code target/classes} and would otherwise
 * silently get the synchronous fallback. The {@code java-21} profile therefore also compiles those
 * sources into {@code test-classes}. Without that, any action that releases the mailbox while
 * waiting — an internal sub-agent call, for instance — blocks the mailbox instead and deadlocks.
 */
public class ContinuationSupportTest {

    @Test
    void continuationsAreAvailableOnTheTestClasspath() {
        assumeTrue(Runtime.version().feature() >= 21, "continuations need JDK 21+");

        assertThat(ContinuationActionExecutor.isContinuationSupported())
                .as(
                        "tests must load the JDK 21 ContinuationActionExecutor, not the"
                                + " synchronous fallback")
                .isTrue();
    }
}
