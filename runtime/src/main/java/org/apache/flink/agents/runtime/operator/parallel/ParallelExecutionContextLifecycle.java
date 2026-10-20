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

import org.apache.flink.agents.runtime.operator.ActionTask;

/**
 * Detaches and restores the shared runner context around a parallel execution ownership handoff.
 * Called by the async executor around the window in which a worker holds no lock, so the shared
 * context is detached from the yielding task before another worker can bind it, and repointed at
 * the task's key, memory, continuation, and durable-execution contexts after ownership is
 * re-acquired.
 */
public interface ParallelExecutionContextLifecycle {

    /** Suspends the task context while the worker still owns the execution lock. */
    void suspend(Object key, ActionTask actionTask);

    /** Restores and prepares the task context after the worker re-acquires the execution lock. */
    void resume(Object key, ActionTask actionTask);
}
