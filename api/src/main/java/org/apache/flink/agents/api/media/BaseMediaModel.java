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

import org.apache.flink.agents.api.chat.messages.DataContentBlock;
import org.apache.flink.agents.api.resource.Resource;
import org.apache.flink.agents.api.resource.ResourceContext;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceType;

import javax.annotation.Nullable;

import java.util.List;
import java.util.Map;

/**
 * A model that generates, transcribes, or synthesizes media.
 *
 * <p>One instance binds a single model and task. The Java type is the caller-facing contract. A
 * provider may be implemented in Python and reached through the cross-language bridge.
 */
public abstract class BaseMediaModel extends Resource {
    protected BaseMediaModel(ResourceDescriptor descriptor, ResourceContext resourceContext) {
        super(descriptor, resourceContext);
    }

    @Override
    public ResourceType getResourceType() {
        return ResourceType.MEDIA_MODEL;
    }

    /**
     * Return the finished result, blocking until it is ready.
     *
     * <p>For asynchronous tasks, this submits a job and waits for its result. A polling timeout
     * does not cancel the provider job; the exception carries its handle for later queries.
     *
     * @param content input blocks; accepted combinations depend on the resource task
     * @param timeout seconds for a synchronous request, or the polling budget after asynchronous
     *     submission; null uses the configured request timeout or polling budget respectively
     * @param parameters provider parameters overriding configured defaults
     * @throws MediaJobException if the asynchronous task ends without a successful result
     * @throws MediaJobTimeoutException if the asynchronous polling budget elapses
     */
    public abstract MediaResult generate(
            List<DataContentBlock> content,
            @Nullable Double timeout,
            Map<String, Object> parameters);

    /** Whether submit, fetch, wait, and cancel are available for this task. */
    public abstract boolean supportsAsyncTask();

    /** Submit one asynchronous task and return its handle. */
    public abstract MediaJob submit(List<DataContentBlock> content, Map<String, Object> parameters);

    /** Read a submitted job's state once, including its result when it has succeeded. */
    public abstract MediaJobState fetch(MediaJob job);

    /**
     * Poll until the job reaches a terminal status or the polling budget elapses.
     *
     * <p>The budget limits when a new query can start. A query already in progress may finish after
     * that budget and return its result. A timeout does not cancel the provider job.
     *
     * @param job handle returned by submit
     * @param timeout polling budget in seconds from the start of this wait; null uses the
     *     configured polling budget
     * @throws MediaJobException if the task fails, is canceled, is unknown, or succeeds without a
     *     result
     * @throws MediaJobTimeoutException if the polling budget elapses before a terminal result
     */
    public abstract MediaResult wait(MediaJob job, @Nullable Double timeout);

    /** Ask the provider to cancel a pending job. */
    public abstract void cancel(MediaJob job);
}
