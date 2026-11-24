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

import org.apache.flink.agents.api.chat.messages.MediaBlock;
import org.apache.flink.agents.api.chat.messages.TextBlock;
import org.apache.flink.agents.api.resource.ResourceContext;
import org.apache.flink.agents.api.resource.ResourceDescriptor;

import javax.annotation.Nullable;

import java.time.Duration;

/**
 * Abstract base class for {@code MEDIA_MODEL} transcription setups.
 *
 * <p>Implementations that back transcription with resumable cloud jobs also implement {@link
 * AsyncJobModel}{@code <TranscriptionRequest, MediaResult<TextBlock>>}.
 */
public abstract class BaseTranscriptionModelSetup extends BaseMediaModelSetup {
    protected BaseTranscriptionModelSetup(
            ResourceDescriptor descriptor, @Nullable ResourceContext resourceContext) {
        super(descriptor, resourceContext);
    }

    /** Transcribe audio or video using setup options and the connection default timeout. */
    public MediaResult<TextBlock> transcribe(MediaBlock audio) {
        return transcribe(TranscriptionRequest.of(audio));
    }

    /** Transcribe with request parameters and the connection default timeout. */
    public MediaResult<TextBlock> transcribe(TranscriptionRequest request) {
        return transcribe(request, null);
    }

    /**
     * Transcribe audio or video. Request parameters override matching setup options. Null timeout
     * uses the connection default (or default polling budget for async setups).
     */
    public abstract MediaResult<TextBlock> transcribe(
            TranscriptionRequest request, @Nullable Duration timeout);
}
