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

package org.apache.flink.agents.runtime.lifecycle;

import org.apache.flink.agents.runtime.operator.ActionTask;
import org.apache.flink.agents.runtime.python.utils.PythonActionExecutor;

/**
 * Forwards the operator's per-record and per-task lifecycle to the Python runtime over the pemja
 * bridge, where the Python side dispatches each callback to its registered listeners. The whole
 * {@link TaskLifecycleListener} contract is forwarded, so a Python listener observes the same
 * lifecycle as any Java listener.
 *
 * <p>A Python-side exception propagates back through the bridge and fails the action, so a Python
 * listener can enforce its own end-of-record or end-of-task invariants like a Java listener.
 */
public final class PythonTaskLifecycleListener implements TaskLifecycleListener {

    private final PythonActionExecutor pythonActionExecutor;

    public PythonTaskLifecycleListener(PythonActionExecutor pythonActionExecutor) {
        this.pythonActionExecutor = pythonActionExecutor;
    }

    @Override
    public void onRecordStart(Object key) {
        pythonActionExecutor.notifyRecordStart(key);
    }

    @Override
    public void onTaskPrepared(ActionTask task) {
        pythonActionExecutor.notifyTaskPrepared(task);
    }

    @Override
    public void onTaskTransferred(ActionTask from, ActionTask to) {
        pythonActionExecutor.notifyTaskTransferred(from, to);
    }

    @Override
    public void onTaskFinished(ActionTask task) {
        pythonActionExecutor.notifyTaskFinished(task);
    }

    @Override
    public void onRecordFinished(Object key) {
        pythonActionExecutor.notifyRecordFinished(key);
    }
}
