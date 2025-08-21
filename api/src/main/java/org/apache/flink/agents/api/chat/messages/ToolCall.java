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

import java.util.Objects;

public class ToolCall {
    private final String id;
    private final String type;
    private final String name;
    private final String arguments;

    public ToolCall(String id, String type, String name, String arguments) {
        this.id = id;
        this.type = type;
        this.name = name;
        this.arguments = arguments;
    }

    public String getId() {
        return id;
    }

    public String getType() {
        return type;
    }

    public String getName() {
        return name;
    }

    public String getArguments() {
        return arguments;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ToolCall)) return false;
        ToolCall toolCall = (ToolCall) o;
        return Objects.equals(id, toolCall.id)
                && Objects.equals(type, toolCall.type)
                && Objects.equals(name, toolCall.name)
                && Objects.equals(arguments, toolCall.arguments);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, type, name, arguments);
    }

    @Override
    public String toString() {
        return "ToolCall{"
                + "id='"
                + id
                + '\''
                + ", type='"
                + type
                + '\''
                + ", name='"
                + name
                + '\''
                + ", arguments='"
                + arguments
                + '\''
                + '}';
    }
}
