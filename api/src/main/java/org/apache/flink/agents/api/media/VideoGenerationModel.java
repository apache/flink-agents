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

import org.apache.flink.agents.api.chat.messages.VideoBlock;

import javax.annotation.Nullable;

import java.time.Duration;

/**
 * Model-bound video generation capability using its provider's client and lifetime.
 *
 * <p>Providers that back video generation with resumable cloud jobs also implement {@link
 * AsyncJobModel}{@code <VideoGenerationRequest, MediaResult<VideoBlock>>}.
 */
public interface VideoGenerationModel {
    /** Generate a video from a prompt using bound options and the provider default timeout. */
    default MediaResult<VideoBlock> generate(String prompt) {
        return generate(VideoGenerationRequest.of(prompt));
    }

    /** Generate a video using a typed request and the provider default timeout. */
    default MediaResult<VideoBlock> generate(VideoGenerationRequest request) {
        return generate(request, null);
    }

    /**
     * Generate a video. Request parameters override matching bound model parameters. Null timeout
     * uses the provider default (or default polling budget for async providers).
     */
    MediaResult<VideoBlock> generate(VideoGenerationRequest request, @Nullable Duration timeout);
}
