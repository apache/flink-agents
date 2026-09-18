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

import org.apache.flink.agents.runtime.operator.ActionTask;

/**
 * Context for continuation execution (base JDK 11 sources). Carries the current key, the {@link
 * ActionTask}, and its (recordIndex, taskIndex) priority so {@link ContinuationActionExecutor} can
 * re-acquire the lock at the right priority and restore the runner context on the parallel path.
 * JDK 21 provides a multi-release variant with continuation-specific state instead.
 */
public class ContinuationContext {

    private Object key;
    private ActionTask actionTask;
    private long recordIndex;
    private long taskIndex;

    public void setKey(Object key) {
        this.key = key;
    }

    public Object getKey() {
        return key;
    }

    public void setActionTask(ActionTask actionTask) {
        this.actionTask = actionTask;
    }

    public ActionTask getActionTask() {
        return actionTask;
    }

    public void setPriority(long recordIndex, long taskIndex) {
        this.recordIndex = recordIndex;
        this.taskIndex = taskIndex;
    }

    public long getRecordIndex() {
        return recordIndex;
    }

    public long getTaskIndex() {
        return taskIndex;
    }
}
