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

/**
 * Text or media data within a {@link ChatMessage} or a tool result.
 *
 * <p>This subset of {@link ContentBlock} contains {@link TextBlock} and the concrete {@link
 * MediaBlock} types. Tool outputs use it to exclude reasoning, tool calls, and nested tool results
 * from their content. Data blocks remain ordinary message blocks and retain their order when mixed
 * with other block types in a message.
 */
@JsonSubTypes({
    @JsonSubTypes.Type(value = TextBlock.class, name = "text"),
    @JsonSubTypes.Type(value = ImageBlock.class, name = "image"),
    @JsonSubTypes.Type(value = AudioBlock.class, name = "audio"),
    @JsonSubTypes.Type(value = VideoBlock.class, name = "video"),
    @JsonSubTypes.Type(value = DocumentBlock.class, name = "document")
})
public abstract class DataContentBlock extends ContentBlock {}
