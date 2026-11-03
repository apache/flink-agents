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
package org.apache.flink.agents.runtime.python.utils;

import org.apache.flink.agents.plan.exceptions.UncapturableException;
import org.junit.jupiter.api.Test;
import pemja.core.PythonInterpreter;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class PythonFaultBridgeTest {
    @Test
    void interpreterStillClosesWhenReleasingTheDispatcherFails() {
        PythonInterpreter interpreter = mock(PythonInterpreter.class);
        RuntimeException releaseFailure = new RuntimeException("release failed");
        RuntimeException closeFailure = new RuntimeException("close failed");
        doThrow(releaseFailure).when(interpreter).set("_flink_agents_bridge", (Object) null);
        doThrow(closeFailure).when(interpreter).close();

        Throwable failure = catchThrowable(() -> PythonFaultBridge.close(interpreter));

        assertThat(failure).isSameAs(releaseFailure);
        assertThat(failure.getSuppressed()).containsExactly(closeFailure);
        verify(interpreter).close();
    }

    @Test
    void malformedProtocolFailsAsAFrameworkFault() {
        Object[] malformed =
                new Object[] {
                    null,
                    "raw value",
                    new Object[] {true},
                    new Object[] {false, null, "Type", "message", null},
                    new Object[] {
                        false, new IOException("ordinary failure"), "Type", "message", "trace"
                    }
                };
        for (Object value : malformed) {
            assertThatThrownBy(() -> PythonFaultBridge.unwrap(value))
                    .isInstanceOf(UncapturableException.class)
                    .hasRootCauseInstanceOf(IllegalStateException.class);
        }
    }
}
