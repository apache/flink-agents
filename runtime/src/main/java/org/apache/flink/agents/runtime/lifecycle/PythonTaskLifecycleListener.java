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
 * Forwards the operator's per-record and per-action lifecycle to the Python runtime over the pemja
 * bridge, where the Python side dispatches each callback to its registered listeners. The whole
 * {@link TaskLifecycleListener} contract is forwarded, so a Python listener observes the same
 * lifecycle as any Java listener.
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
    public void onActionPrepared(ActionTask task) {
        pythonActionExecutor.notifyActionPrepared(task);
    }

    @Override
    public void onActionStarted(ActionTask task) {
        pythonActionExecutor.notifyActionStarted(task);
    }

    @Override
    public void onActionTransferred(ActionTask from, ActionTask to) {
        pythonActionExecutor.notifyActionTransferred(from, to);
    }

    @Override
    public void onActionFinishing(ActionTask task) {
        pythonActionExecutor.notifyActionFinishing(task);
    }

    @Override
    public void onActionFinished(ActionTask task) {
        pythonActionExecutor.notifyActionFinished(task);
    }

    @Override
    public void onActionReused(ActionTask task) {
        pythonActionExecutor.notifyActionReused(task);
    }

    @Override
    public void onActionFailed(ActionTask task, Throwable error) {
        pythonActionExecutor.notifyActionFailed(task, error);
    }

    @Override
    public void onRecordFinished(Object key) {
        pythonActionExecutor.notifyRecordFinished(key);
    }
}
