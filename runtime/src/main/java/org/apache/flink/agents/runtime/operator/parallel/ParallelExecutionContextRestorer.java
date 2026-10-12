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
 * Restores the shared runner context to a resuming action task. Called by the async executor after
 * a worker re-acquires the lock, so the shared context is repointed at the task's key, memory,
 * continuation, and durable-execution contexts before control returns to user code.
 */
@FunctionalInterface
public interface ParallelExecutionContextRestorer {

    void restore(Object key, ActionTask actionTask);
}
