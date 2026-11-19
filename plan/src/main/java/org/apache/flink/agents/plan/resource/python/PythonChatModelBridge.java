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
package org.apache.flink.agents.plan.resource.python;

import pemja.core.PythonException;
import pemja.core.object.PyObject;

import java.util.Map;

/**
 * Invokes a Python chat model setup or connection while preserving HTTP status codes that Pemja's
 * generic exception conversion would otherwise flatten into a statusless {@link PythonException}.
 */
public final class PythonChatModelBridge {
    static final String CALL_CHAT_MODEL = "python_java_utils.call_chat_model";

    private PythonChatModelBridge() {}

    static Object chat(
            PythonResourceAdapter adapter,
            PythonObjectScope scope,
            PyObject resource,
            Map<String, Object> kwargs) {
        Object value = scope.own(adapter.invoke(CALL_CHAT_MODEL, resource, kwargs));
        if (!(value instanceof Object[])) {
            throw new IllegalStateException("Invalid ChatModel invocation outcome from Python");
        }
        Object[] envelope = (Object[]) value;
        if (envelope.length == 2 && Boolean.TRUE.equals(envelope[0])) {
            return envelope[1];
        }
        if (envelope.length == 3
                && Boolean.FALSE.equals(envelope[0])
                && envelope[1] instanceof Number
                && envelope[2] instanceof String) {
            throw rethrowUnchecked(
                    new PythonHttpStatusException(
                            ((Number) envelope[1]).intValue(), (String) envelope[2]));
        }
        throw new IllegalStateException("Invalid ChatModel invocation outcome from Python");
    }

    @SuppressWarnings("unchecked")
    private static <E extends Throwable> RuntimeException rethrowUnchecked(Throwable error)
            throws E {
        throw (E) error;
    }

    /**
     * Carries an HTTP status from a Python chat provider across Pemja while remaining a {@link
     * PythonException}.
     */
    public static final class PythonHttpStatusException extends PythonException {
        private final int statusCode;

        public PythonHttpStatusException(int statusCode, String message) {
            super(message);
            this.statusCode = statusCode;
        }

        public int statusCode() {
            return statusCode;
        }
    }
}
