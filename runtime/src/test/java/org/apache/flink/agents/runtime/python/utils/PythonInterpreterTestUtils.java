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
import pemja.core.PythonInterpreter;

import java.util.Arrays;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

/** Keeps unit-test interpreter stubs expressed in terms of the Python entry point they exercise. */
public final class PythonInterpreterTestUtils {
    private PythonInterpreterTestUtils() {}

    public static PythonInterpreter mockInterpreter() {
        PythonInterpreter interpreter = mock(PythonInterpreter.class);
        enableFaultEnvelope(interpreter);
        return interpreter;
    }

    public static void enableFaultEnvelope(PythonInterpreter interpreter) {
        doAnswer(
                        invocation -> {
                            Object[] call = (Object[]) invocation.getRawArguments()[1];
                            try {
                                Object result =
                                        interpreter.invoke(
                                                (String) call[0],
                                                Arrays.copyOfRange(call, 1, call.length));
                                return new Object[] {true, result};
                            } catch (Exception failure) {
                                if (!UncapturableException.isUncapturable(failure)) {
                                    throw failure;
                                }
                                return new Object[] {
                                    false, failure, "RuntimeError", failure.getMessage(), null
                                };
                            }
                        })
                .when(interpreter)
                .invoke(eq(PythonFaultBridge.ENTRY_POINT), any(Object[].class));
    }
}
