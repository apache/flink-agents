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

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * {@link ThreadFactory} for the Flink Agents Java async executor, producing descriptive,
 * collision-resistant thread names of the form {@code
 * flink-agents-java-async-<pool-id>-thread-<worker-id>}.
 *
 * <p>Default executor names such as {@code pool-N-thread-M} make Flink Agents async workers hard to
 * attribute in TaskManager thread dumps and profiler output, where many unrelated pools coexist.
 * The pool id is process-unique so multiple executor instances in one TaskManager remain
 * distinguishable; only the name changes — thread priority and daemon status follow the default
 * factory behavior.
 */
public final class AsyncExecutorThreadFactory implements ThreadFactory {

    private static final AtomicInteger POOL_ID = new AtomicInteger();

    private final String namePrefix;
    private final AtomicInteger workerId = new AtomicInteger();

    public AsyncExecutorThreadFactory() {
        this.namePrefix = "flink-agents-java-async-" + POOL_ID.incrementAndGet() + "-thread-";
    }

    @Override
    public Thread newThread(Runnable runnable) {
        return new Thread(runnable, namePrefix + workerId.incrementAndGet());
    }
}
