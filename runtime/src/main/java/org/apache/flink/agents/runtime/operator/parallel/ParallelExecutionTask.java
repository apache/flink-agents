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
package org.apache.flink.agents.runtime.operator.parallel;

import javax.annotation.Nullable;

/**
 * A self-describing unit of worker execution for the parallel execution engine. A pool thread
 * {@link #setup} + {@link #execute()}s it (pull, prepare, run; result or failure stored inside);
 * the mailbox thread later {@link #restoreContext()} + {@link #commit()}s each done key-group head
 * in order and calls {@link #finishGroup} once per drain.
 */
public interface ParallelExecutionTask {

    /**
     * Binds this work to a key and its coordinator-assigned (recordIndex, taskIndex) priority.
     * Called once on the worker thread under the lock, before {@link #execute()}.
     */
    void setup(Object key, long recordIndex, long taskIndex);

    /** Re-establishes the runner context and Flink key for this work (used by the commit drain). */
    void restoreContext();

    /**
     * Runs the action on a worker thread: pulls and prepares the next queued task, invokes the
     * action, and stores the result or failure inside this work. Never throws; failures are
     * rethrown by {@link #commit()}.
     */
    void execute();

    /** Whether {@link #execute()} has finished (result or failure recorded). */
    boolean isDone();

    /** Commits this work's result on the mailbox thread; rethrows a recorded execution failure. */
    void commit() throws Exception;

    /**
     * Runs the per-key-group follow-up after the commit drain; called once on the completing work.
     *
     * @param lastCommitted the last work committed in this drain, or {@code null} if none was.
     */
    void finishGroup(@Nullable ParallelExecutionTask lastCommitted) throws Exception;
}
