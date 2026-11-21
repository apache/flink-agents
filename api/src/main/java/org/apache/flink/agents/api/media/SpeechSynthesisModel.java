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

import javax.annotation.Nullable;

import java.time.Duration;

/** Model-bound speech synthesis using its provider's client and lifetime. */
public interface SpeechSynthesisModel {
    /** Synthesize speech using the bound provider options. */
    default MediaResult<AudioBlock> synthesize(String text) {
        return synthesize(SpeechSynthesisRequest.of(text));
    }

    /** Synthesize speech with request parameters using the provider's default timeout. */
    default MediaResult<AudioBlock> synthesize(SpeechSynthesisRequest request) {
        return synthesize(request, null);
    }

    /**
     * Synthesize speech. Request parameters override matching bound model parameters. Null timeout
     * uses the provider's default timeout.
     */
    MediaResult<AudioBlock> synthesize(SpeechSynthesisRequest request, @Nullable Duration timeout);
}
