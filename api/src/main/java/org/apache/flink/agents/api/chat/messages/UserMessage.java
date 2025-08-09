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

import org.apache.flink.agents.api.util.Resource;

import java.util.HashMap;
import java.util.Map;

public class UserMessage extends AbstractMessage {

    public UserMessage(String textContent) {
        this(textContent, Map.of());
    }

    public UserMessage(String textContent, Map<String, Object> metadata) {
        super(MessageType.USER, textContent, metadata);
    }

    public UserMessage(Resource resource) {
        this(MessageUtils.readResource(resource));
    }

    @Override
    public String toString() {
        return "UserMessage{"
                + "content='"
                + getText()
                + '\''
                + ", properties="
                + this.metadata
                + ", messageType="
                + this.messageType
                + '}';
    }

    @Override
    public String getText() {
        return this.textContent;
    }

    public UserMessage copy() {
        return new Builder().text(getText()).metadata(new HashMap<>(getMetadata())).build();
    }

    public Builder mutate() {
        return new Builder().text(getText()).metadata(new HashMap<>(getMetadata()));
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {

        private String textContent;

        private Resource resource;

        private Map<String, Object> metadata = new HashMap<>();

        public Builder text(String textContent) {
            this.textContent = textContent;
            return this;
        }

        public Builder text(Resource resource) {
            this.resource = resource;
            return this;
        }

        public Builder metadata(Map<String, Object> metadata) {
            this.metadata = metadata;
            return this;
        }

        public UserMessage build() {
            if (this.textContent != null && !this.textContent.isEmpty() && this.resource != null) {
                throw new IllegalArgumentException(
                        "textContent and resource cannot be set at the same time");
            } else if (this.resource != null) {
                this.textContent = MessageUtils.readResource(this.resource);
            }
            return new UserMessage(this.textContent, this.metadata);
        }
    }
}
