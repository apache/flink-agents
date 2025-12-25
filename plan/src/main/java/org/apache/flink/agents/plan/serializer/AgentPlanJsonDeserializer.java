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

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.ObjectCodec;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.plan.AgentConfiguration;
import org.apache.flink.agents.plan.AgentPlan;
import org.apache.flink.agents.plan.actions.Action;
import org.apache.flink.agents.plan.resourceprovider.ResourceProvider;

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

        // Deserialize resource providers
        JsonNode resourceProvidersNode = node.get("resource_providers");
        JavaType resourceProviderType = ctx.constructType(ResourceProvider.class);
        JsonDeserializer<?> resourceProviderDeserializer =
                ctx.findContextualValueDeserializer(resourceProviderType, null);
        Map<ResourceType, Map<String, ResourceProvider>> resourceProviders = new HashMap<>();
        if (resourceProvidersNode != null && resourceProvidersNode.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> iterator = resourceProvidersNode.fields();
            while (iterator.hasNext()) {
                Map.Entry<String, JsonNode> entry = iterator.next();
                String resourceType = entry.getKey();
                JsonNode providers = entry.getValue();
                Iterator<Map.Entry<String, JsonNode>> providerIterator = providers.fields();
                Map<String, ResourceProvider> nameToProvider = new HashMap<>();
                while (providerIterator.hasNext()) {
                    Map.Entry<String, JsonNode> providerEntry = providerIterator.next();
                    String name = providerEntry.getKey();
                    JsonNode provider = providerEntry.getValue();
                    JsonParser resourceProviderParser = codec.treeAsTokens(provider);
                    ResourceProvider resourceProvider =
                            (ResourceProvider)
                                    resourceProviderDeserializer.deserialize(
                                            resourceProviderParser, ctx);
                    nameToProvider.put(name, resourceProvider);
                }
                resourceProviders.put(ResourceType.fromValue(resourceType), nameToProvider);
            }
        }

        // Deserialize config data
        JsonNode configNode = node.get("config");
        Map<String, Object> configData = new HashMap<>();
        if (configNode != null && configNode.isObject()) {
            JsonNode configDataNode = configNode.get("conf_data");
            if (configDataNode != null && configDataNode.isObject()) {
                ObjectMapper mapper = new ObjectMapper();
                configData = mapper.convertValue(configDataNode, Map.class);
            }
        }
        AgentConfiguration config = new AgentConfiguration(configData);

        return new AgentPlan(actions, actionsByEvent, resourceProviders, config);
    }
}
