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
package org.apache.flink.agents.plan.resource.python;

import org.apache.flink.agents.api.chat.messages.ChatMessage;
import org.apache.flink.agents.api.chat.messages.MessageRole;
import org.apache.flink.agents.api.prompt.Prompt;
import org.apache.flink.agents.api.resource.python.PythonResourceAdapter;
import org.apache.flink.agents.api.resource.python.PythonResourceWrapper;
import pemja.core.object.PyObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class PythonMCPPrompt extends Prompt implements PythonResourceWrapper {
    private static final String FROM_JAVA_MESSAGE_ROLE = "python_java_utils.from_java_message_role";

    private final PyObject prompt;
    private final PythonResourceAdapter adapter;
    private String name;

    public PythonMCPPrompt(PythonResourceAdapter adapter, PyObject prompt) {
        this.adapter = adapter;
        this.prompt = prompt;
    }

    @Override
    public Object getPythonResource() {
        return prompt;
    }

    public String getName() {
        if (name == null) {
            name = prompt.getAttr("name").toString();
        }
        return name;
    }

    @Override
    public String formatString(Map<String, String> kwargs) {
        Map<String, Object> parameters = new HashMap<>(kwargs);
        return adapter.callMethod(prompt, "format_string", parameters).toString();
    }

    @Override
    public List<ChatMessage> formatMessages(MessageRole defaultRole, Map<String, String> kwargs) {
        Map<String, Object> parameters = new HashMap<>(kwargs);
        Object pythonRole = adapter.invoke(FROM_JAVA_MESSAGE_ROLE, defaultRole);
        parameters.put("role", pythonRole);

        Object result = adapter.callMethod(prompt, "format_messages", parameters);
        if (result instanceof List) {
            List<Object> pythonMessages = (List<Object>) result;
            List<ChatMessage> messages = new ArrayList<>(pythonMessages.size());
            for (Object pythonMessage : pythonMessages) {
                messages.add(adapter.fromPythonChatMessage(pythonMessage));
            }
            return messages;
        }
        return Collections.emptyList();
    }
}
