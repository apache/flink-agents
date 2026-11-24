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

import org.apache.flink.agents.api.chat.messages.AudioBlock;
import org.apache.flink.agents.api.resource.ResourceContext;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.utils.StringValidationUtils;

import javax.annotation.Nullable;

import java.time.Duration;

/** Abstract base class for {@code MEDIA_MODEL} speech synthesis setups. */
public abstract class BaseSpeechSynthesisModelSetup extends BaseMediaModelSetup {
    @Nullable private final String voice;

    protected BaseSpeechSynthesisModelSetup(
            ResourceDescriptor descriptor, @Nullable ResourceContext resourceContext) {
        super(descriptor, resourceContext);
        this.voice =
                StringValidationUtils.optionalNonBlank(descriptor.getArgument("voice"), "voice");
    }

    @Nullable
    public String getVoice() {
        return voice;
    }

    /** Synthesize speech using the configured setup options. */
    public MediaResult<AudioBlock> synthesize(String text) {
        return synthesize(SpeechSynthesisRequest.of(text));
    }

    /** Synthesize speech with request parameters using the connection's default timeout. */
    public MediaResult<AudioBlock> synthesize(SpeechSynthesisRequest request) {
        return synthesize(request, null);
    }

    /**
     * Synthesize speech. Request parameters override matching setup options. Null timeout uses the
     * connection's default timeout.
     */
    public abstract MediaResult<AudioBlock> synthesize(
            SpeechSynthesisRequest request, @Nullable Duration timeout);
}
