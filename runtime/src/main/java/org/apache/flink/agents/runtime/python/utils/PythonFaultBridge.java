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
import org.apache.flink.util.ExceptionUtils;
import pemja.core.PythonException;
import pemja.core.PythonInterpreter;

/** Preserves marked faults before Pemja turns a Python exception into a cause-less exception. */
final class PythonFaultBridge {
    static final String ENTRY_POINT = "_flink_agents_bridge.invoke";
    // Pemja's bare-global lookup caches a borrowed reference and decrefs it when the next
    // invocation uses another name. Attribute lookup owns its reference, so keep our callable
    // on a per-interpreter namespace rather than invoking a bare global function.
    static final String INITIALIZER =
            "from flink_agents.plan import exceptions as _flink_agents_exceptions\n"
                    + "from functools import partial as _flink_agents_partial\n"
                    + "from types import SimpleNamespace as _flink_agents_namespace\n"
                    + "_flink_agents_bridge = _flink_agents_namespace(invoke=_flink_agents_partial("
                    + "_flink_agents_exceptions.invoke_with_framework_faults, globals()))";

    private PythonFaultBridge() {}

    static Object invoke(PythonInterpreter interpreter, String name, Object... args) {
        Object[] bridgeArgs = new Object[args.length + 1];
        bridgeArgs[0] = name;
        System.arraycopy(args, 0, bridgeArgs, 1, args.length);
        return unwrap(interpreter.invoke(ENTRY_POINT, bridgeArgs));
    }

    static void close(PythonInterpreter interpreter) {
        Throwable failure = null;
        try {
            // Break namespace -> partial -> globals before Pemja drops its callable cache.
            interpreter.set("_flink_agents_bridge", (Object) null);
        } catch (Throwable error) {
            failure = error;
        }
        try {
            interpreter.close();
        } catch (Throwable error) {
            failure = ExceptionUtils.firstOrSuppressed(error, failure);
        }
        if (failure != null) {
            ExceptionUtils.rethrow(failure);
        }
    }

    static Object unwrap(Object value) {
        if (!(value instanceof Object[])) {
            throw invalidEnvelope();
        }
        Object[] envelope = (Object[]) value;
        if (envelope.length == 2 && Boolean.TRUE.equals(envelope[0])) {
            return envelope[1];
        }
        if (envelope.length != 5
                || !Boolean.FALSE.equals(envelope[0])
                || !(envelope[2] instanceof String)
                || !(envelope[3] instanceof String)
                || (envelope[4] != null && !(envelope[4] instanceof String))) {
            throw invalidEnvelope();
        }
        Throwable javaFault = null;
        if (envelope[1] != null) {
            if (!(envelope[1] instanceof Throwable)
                    || !UncapturableException.isUncapturable((Throwable) envelope[1])) {
                throw invalidEnvelope();
            }
            javaFault = (Throwable) envelope[1];
            if (envelope[4] == null) {
                throw UncapturableException.wrap(javaFault);
            }
        }
        if (javaFault == null && envelope[4] == null) {
            throw invalidEnvelope();
        }
        PythonException pythonFault =
                new PythonException(envelope[2] + ": " + envelope[3] + "\n" + envelope[4]);
        if (javaFault != null) {
            pythonFault.initCause(javaFault);
        }
        throw UncapturableException.wrap(pythonFault);
    }

    private static UncapturableException invalidEnvelope() {
        return UncapturableException.wrap(
                new IllegalStateException("Invalid Python framework-fault envelope"));
    }
}
