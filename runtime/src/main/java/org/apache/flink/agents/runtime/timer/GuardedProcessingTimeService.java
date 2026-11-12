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
package org.apache.flink.agents.runtime.timer;

import org.apache.flink.streaming.runtime.tasks.ProcessingTimeService;
import org.apache.flink.util.clock.Clock;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;

/** Wraps the entire native timer callback, including Flink's current-key switch. */
public final class GuardedProcessingTimeService implements ProcessingTimeService {
    @FunctionalInterface
    public interface CallbackRunner {
        void run(ProcessingTimeCallback callback, long timestamp) throws Exception;
    }

    private final ProcessingTimeService delegate;
    private final CallbackRunner runner;

    public GuardedProcessingTimeService(ProcessingTimeService delegate, CallbackRunner runner) {
        this.delegate = delegate;
        this.runner = runner;
    }

    @Override
    public Clock getClock() {
        return delegate.getClock();
    }

    @Override
    public long getCurrentProcessingTime() {
        return delegate.getCurrentProcessingTime();
    }

    @Override
    public ScheduledFuture<?> registerTimer(long timestamp, ProcessingTimeCallback callback) {
        return delegate.registerTimer(timestamp, time -> runner.run(callback, time));
    }

    @Override
    public ScheduledFuture<?> scheduleAtFixedRate(
            ProcessingTimeCallback callback, long initialDelay, long period) {
        return delegate.scheduleAtFixedRate(
                time -> runner.run(callback, time), initialDelay, period);
    }

    @Override
    public ScheduledFuture<?> scheduleWithFixedDelay(
            ProcessingTimeCallback callback, long initialDelay, long period) {
        return delegate.scheduleWithFixedDelay(
                time -> runner.run(callback, time), initialDelay, period);
    }

    @Override
    public CompletableFuture<Void> quiesce() {
        return delegate.quiesce();
    }
}
