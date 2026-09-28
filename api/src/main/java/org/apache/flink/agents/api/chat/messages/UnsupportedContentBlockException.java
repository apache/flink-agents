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

/**
 * Thrown when a chat model integration cannot send a {@link ContentBlock} to its provider, such as
 * a media type the provider does not accept or a media block in a message role that only takes
 * text. Integrations throw it rather than silently dropping or converting the block.
 *
 * <p>Messages name the block type, media type and source type only, never the media payload or URL.
 */
public class UnsupportedContentBlockException extends IllegalArgumentException {

    private static final long serialVersionUID = 1L;

    public UnsupportedContentBlockException(String message) {
        super(message);
    }
}
