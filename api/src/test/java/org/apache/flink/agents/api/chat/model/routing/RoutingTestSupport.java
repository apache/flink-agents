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

package org.apache.flink.agents.api.chat.model.routing;

import org.apache.flink.agents.api.chat.messages.ChatMessage;
import org.apache.flink.agents.api.chat.messages.MessageRole;
import org.apache.flink.agents.api.chat.model.BaseChatModelSetup;
import org.apache.flink.agents.api.resource.Resource;
import org.apache.flink.agents.api.resource.ResourceContext;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Shared fakes for the routing tests. */
final class RoutingTestSupport {

    private RoutingTestSupport() {}

    static ResourceDescriptor emptyDescriptor(Class<?> clazz) {
        return new ResourceDescriptor(clazz.getName(), Collections.emptyMap());
    }

    /** A chat model that records the messages it received and answers with its own tag. */
    static final class RecordingModel extends BaseChatModelSetup {
        final String tag;
        int callCount = 0;
        List<ChatMessage> lastMessages;

        RecordingModel(String tag) {
            super(emptyDescriptor(RecordingModel.class), null);
            this.tag = tag;
        }

        @Override
        public Map<String, Object> getParameters() {
            return Collections.emptyMap();
        }

        @Override
        public ChatMessage chat(
                List<ChatMessage> messages,
                Map<String, Object> promptArgs,
                Map<String, Object> modelParams) {
            this.callCount++;
            this.lastMessages = new ArrayList<>(messages);
            return new ChatMessage(MessageRole.ASSISTANT, "handled-by:" + tag);
        }
    }

    /** A chat model that always fails — used to exercise fallback behavior. */
    static final class FailingModel extends BaseChatModelSetup {
        int callCount = 0;

        FailingModel() {
            super(emptyDescriptor(FailingModel.class), null);
        }

        @Override
        public Map<String, Object> getParameters() {
            return Collections.emptyMap();
        }

        @Override
        public ChatMessage chat(
                List<ChatMessage> messages,
                Map<String, Object> promptArgs,
                Map<String, Object> modelParams) {
            this.callCount++;
            throw new RuntimeException("boom from failing model");
        }
    }

    /** A chat model that returns a scripted reply — used as an LLM-as-router judge. */
    static final class ScriptedJudge extends BaseChatModelSetup {
        final String reply;
        int callCount = 0;
        List<ChatMessage> lastMessages;

        ScriptedJudge(String reply) {
            super(emptyDescriptor(ScriptedJudge.class), null);
            this.reply = reply;
        }

        @Override
        public Map<String, Object> getParameters() {
            return Collections.emptyMap();
        }

        @Override
        public ChatMessage chat(
                List<ChatMessage> messages,
                Map<String, Object> promptArgs,
                Map<String, Object> modelParams) {
            this.callCount++;
            this.lastMessages = new ArrayList<>(messages);
            return new ChatMessage(MessageRole.ASSISTANT, reply);
        }
    }

    /** A {@link ResourceContext} backed by a fixed name → resource map. */
    static ResourceContext context(Map<String, Resource> byName) {
        return ResourceContext.fromGetResource(
                (name, type) -> {
                    Resource resource = byName.get(name);
                    if (resource == null) {
                        throw new RuntimeException("No resource registered for name: " + name);
                    }
                    return resource;
                });
    }

    static ChatMessage user(String content) {
        return new ChatMessage(MessageRole.USER, content);
    }

    static RoutingContext routingContext(
            List<ChatMessage> messages,
            List<RoutingCandidate> candidates,
            ResourceContext resourceContext) {
        return new RoutingContext(messages, Collections.emptyMap(), candidates, resourceContext);
    }

    static ResourceType chatModelType() {
        return ResourceType.CHAT_MODEL;
    }
}
