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

package org.apache.flink.agents.plan.serializer;

import org.apache.flink.agents.plan.Action;
import org.apache.flink.agents.plan.AgentPlan;
import org.apache.flink.shaded.jackson2.com.fasterxml.jackson.core.JacksonException;
import org.apache.flink.shaded.jackson2.com.fasterxml.jackson.core.JsonParser;
import org.apache.flink.shaded.jackson2.com.fasterxml.jackson.core.ObjectCodec;
import org.apache.flink.shaded.jackson2.com.fasterxml.jackson.databind.DeserializationContext;
import org.apache.flink.shaded.jackson2.com.fasterxml.jackson.databind.JavaType;
import org.apache.flink.shaded.jackson2.com.fasterxml.jackson.databind.JsonDeserializer;
import org.apache.flink.shaded.jackson2.com.fasterxml.jackson.databind.JsonNode;
import org.apache.flink.shaded.jackson2.com.fasterxml.jackson.databind.deser.std.StdDeserializer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public class AgentPlanJsonDeserializer extends StdDeserializer<AgentPlan> {

    public AgentPlanJsonDeserializer() {
        super(AgentPlan.class);
    }

    @Override
    public AgentPlan deserialize(JsonParser parser, DeserializationContext ctx)
            throws IOException, JacksonException {
        ObjectCodec codec = parser.getCodec();
        JsonNode node = codec.readTree(parser);
        JsonNode actionsNode = node.get("actions");

        // Deserialize actions
        JavaType actionType = ctx.constructType(Action.class);
        JsonDeserializer<?> actionDeserializer =
                ctx.findContextualValueDeserializer(actionType, null);
        Map<String, Action> actions = new HashMap<>();
        if (actionsNode != null && actionsNode.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> iterator = actionsNode.fields();
            while (iterator.hasNext()) {
                Map.Entry<String, JsonNode> entry = iterator.next();
                String actionName = entry.getKey();
                JsonNode actionNode = entry.getValue();
                JsonParser actionParser = codec.treeAsTokens(actionNode);
                Action action = (Action) actionDeserializer.deserialize(actionParser, ctx);
                actions.put(actionName, action);
            }
        }

        // Deserialize event trigger actions
        JsonNode actionsByEventNode = node.get("actions_by_event");
        Map<String, List<Action>> actionsByEvent = new HashMap<>();
        if (actionsByEventNode != null && actionsByEventNode.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> iterator = actionsByEventNode.fields();
            while (iterator.hasNext()) {
                Map.Entry<String, JsonNode> entry = iterator.next();
                String eventClassName = entry.getKey();
                JsonNode actionsArrayNode = entry.getValue();
                List<Action> actionsTriggeredByEvent = new ArrayList<>();
                for (JsonNode actionNameNode : actionsArrayNode) {
                    String actionName = actionNameNode.asText();
                    Action action = actions.get(actionName);
                    if (action == null) {
                        throw new IllegalStateException("Unknown action name: " + actionName);
                    }
                    actionsTriggeredByEvent.add(action);
                }
                actionsByEvent.put(eventClassName, actionsTriggeredByEvent);
            }
        }

        return new AgentPlan(actions, actionsByEvent);
    }
}
