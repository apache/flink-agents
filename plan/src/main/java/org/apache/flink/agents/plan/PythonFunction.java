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
    private static final String CALL_PYTHON_FUNCTION = "function.call_python_function";

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
        if (interpreter == null) {
            throw new IllegalStateException("Python interpreter is not set.");
        }

        return interpreter.invoke(CALL_PYTHON_FUNCTION, module, qualName, args);
    }

    // TODO: check Python function signature compatibility with given parameter types
    @Override
    public void checkSignature(Class<?>[] parameterTypes) throws Exception {
        throw new UnsupportedOperationException();
    }

    public String getModule() {
        return module;
    }

    public String getQualName() {
        return qualName;
    }
}
