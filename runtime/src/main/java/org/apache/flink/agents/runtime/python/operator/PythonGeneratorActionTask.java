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
package org.apache.flink.agents.runtime.python.operator;

import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.plan.Action;
import org.apache.flink.agents.runtime.context.RunnerContextImpl;
import org.apache.flink.agents.runtime.operator.ActionTask;
import org.apache.flink.agents.runtime.python.utils.PythonActionExecutor;

/** An {@link ActionTask} wrapper a Python Generator to represent a code block in Python action. */
public class PythonGeneratorActionTask extends PythonActionTask {
    private final String pythonGeneratorVarName;

    public PythonGeneratorActionTask(
            Object key,
            Event event,
            Action action,
            PythonActionExecutor pythonActionExecutor,
            RunnerContextImpl runnerContext,
            String pythonGeneratorVarName) {
        super(key, event, action, pythonActionExecutor, runnerContext);
        this.pythonGeneratorVarName = pythonGeneratorVarName;
    }

    @Override
    public boolean invoke() throws Exception {
        LOG.debug(
                "Try execute python generator action {} for event {} with key {}.",
                action.getName(),
                event,
                key);
        boolean finished = pythonActionExecutor.callPythonGenerator(pythonGeneratorVarName).f0;
        if (!finished) {
            generatedActionTask = this;
        }

        return finished;
    }
}
