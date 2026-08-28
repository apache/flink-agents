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

package org.apache.flink.agents.api.chat.messages;

/** The audio content of a {@link ChatMessage} — see {@link MediaBlock} for the media shape. */
public final class AudioBlock extends MediaBlock {

    public AudioBlock() {}

    private AudioBlock(String mimeType, String data, String url) {
        super(mimeType, data, url);
    }

    /** Creates an audio block carrying an inline base64 payload. */
    public static AudioBlock fromBase64(String mimeType, String data) {
        return new AudioBlock(mimeType, data, null);
    }

    /** Creates an audio block referencing an externally managed URL or provider file URI. */
    public static AudioBlock fromUrl(String mimeType, String url) {
        return new AudioBlock(mimeType, null, url);
    }
}
