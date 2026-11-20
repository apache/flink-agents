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

package org.apache.flink.agents.api.media;

import javax.annotation.Nullable;

import java.time.Duration;

/**
 * Orthogonal contract implemented by media capabilities backed by resumable provider-side jobs.
 *
 * <p>Synchronous providers implement only the task-specific model interface (such as {@link
 * SpeechSynthesisModel} or {@link ImageGenerationModel}) and do not implement this interface.
 */
public interface AsyncJobModel<Req, Res extends MediaResult<?>> {
    /** Default polling budget used by {@link #wait(MediaJob)}. */
    Duration DEFAULT_WAIT_TIMEOUT = Duration.ofMinutes(30);

    /** Default interval between job status queries. */
    Duration DEFAULT_POLL_INTERVAL = Duration.ofSeconds(5);

    /** Submit a request and return a durable job handle without waiting. */
    MediaJob submit(Req request);

    /** Query once, retrieving the typed result when the job has succeeded. */
    MediaJobState<Res> fetch(MediaJob job);

    /** Wait using {@link #DEFAULT_WAIT_TIMEOUT} and {@link #DEFAULT_POLL_INTERVAL}. */
    default Res wait(MediaJob job) {
        return wait(job, DEFAULT_WAIT_TIMEOUT);
    }

    /**
     * Poll until completion using {@link #DEFAULT_POLL_INTERVAL}. Pass {@code null} for unlimited
     * polling.
     */
    default Res wait(MediaJob job, @Nullable Duration timeout) {
        return wait(job, timeout, DEFAULT_POLL_INTERVAL);
    }

    /**
     * Poll until completion. Pass {@code null} timeout for unlimited polling. The budget limits
     * when a new query can start; an in-flight query may return after it expires.
     *
     * @param job durable job handle returned by {@link #submit(Object)}
     * @param timeout non-negative polling budget, or {@code null} for unlimited polling
     * @param pollInterval positive delay between status queries
     * @throws MediaJobException if the job ends without a successful result
     * @throws MediaJobTimeoutException if the polling budget expires; the job remains queryable
     */
    Res wait(MediaJob job, @Nullable Duration timeout, Duration pollInterval);

    /** Ask the provider to cancel a pending job. */
    void cancel(MediaJob job);
}
