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

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * A single, typed part of a {@link ChatMessage}'s content.
 *
 * <p>Blocks are ordered within a message. The concrete type answers how providers route the content
 * ({@link TextBlock}, {@link ImageBlock}, {@link AudioBlock}, {@link VideoBlock}, {@link
 * DocumentBlock}), while media encoding is carried by the MIME type on {@link MediaBlock}.
 *
 * <p>The serialized form carries a {@code type} discriminator with fixed values ({@code text},
 * {@code image}, {@code audio}, {@code video}, {@code document}) shared with the Python API, so
 * blocks cross the Java/Python boundary as plain JSON.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = TextBlock.class, name = "text"),
    @JsonSubTypes.Type(value = ImageBlock.class, name = "image"),
    @JsonSubTypes.Type(value = AudioBlock.class, name = "audio"),
    @JsonSubTypes.Type(value = VideoBlock.class, name = "video"),
    @JsonSubTypes.Type(value = DocumentBlock.class, name = "document")
})
public abstract class ContentBlock {}
