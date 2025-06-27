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

import pemja.core.PythonInterpreter;

/** Represent a Python function. */
public class PythonFunction implements Function {

    private final String module;
    private final String qualName;

    private transient PythonInterpreter interpreter;

    public PythonFunction(String module, String qualName) {
        this.module = module;
        this.qualName = qualName;
    }

    public void setInterpreter(PythonInterpreter interpreter) {
        this.interpreter = interpreter;
    }

    @Override
    public Object call(Object... args) throws Exception {
        if (args.length < 1) {
            throw new IllegalArgumentException("Expected at least 1 arguments: PythonEvent");
        }
        byte[] pythonEvent = (byte[]) args[0];

        if (interpreter == null) {
            throw new IllegalStateException("Python interpreter is not set.");
        }
        return interpreter.invokeMethod(
                "python_function_wrapper", "call_python_function", module, qualName, pythonEvent);
    }

    // TODO: check Python function signature compatibility with given parameter types
    @Override
    public void checkSignature(Class<?>[] parameterTypes) throws Exception {
        throw new UnsupportedOperationException();
    }
}
