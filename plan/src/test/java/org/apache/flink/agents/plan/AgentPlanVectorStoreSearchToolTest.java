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

package org.apache.flink.agents.plan;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.api.EventType;
import org.apache.flink.agents.api.agents.Agent;
import org.apache.flink.agents.api.annotation.Action;
import org.apache.flink.agents.api.annotation.Tool;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.api.resource.ResourceContext;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.api.resource.TestResourceContexts;
import org.apache.flink.agents.api.tools.ToolParameters;
import org.apache.flink.agents.api.tools.VectorStoreSearchTool;
import org.apache.flink.agents.api.vectorstores.BaseVectorStore;
import org.apache.flink.agents.api.vectorstores.VectorStoreQueryResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Tests {@link VectorStoreSearchTool} declared on an agent and restored from plan JSON. */
class AgentPlanVectorStoreSearchToolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static VectorStoreSearchTool searchTool(String name) {
        return VectorStoreSearchTool.builder()
                .name(name)
                .description("Search the docs.")
                .vectorStore("knowledge_base")
                .maxResults(5)
                .build();
    }

    /** Declares the tool as an annotated field. */
    static class FieldAgent extends Agent {
        @Tool private final VectorStoreSearchTool searchDocs = searchTool("searchDocs");

        @Action(EventType.InputEvent)
        public void onInput(Event e, RunnerContext ctx) {}
    }

    /** Declares the tool through the programmatic API. */
    static class ProgrammaticAgent extends Agent {
        ProgrammaticAgent() {
            addResource("search_docs", ResourceType.TOOL, searchTool("search_docs"));
        }

        @Action(EventType.InputEvent)
        public void onInput(Event e, RunnerContext ctx) {}
    }

    private static VectorStoreSearchTool restoreAndOpen(
            Agent agent, String toolName, ResourceContext context) throws Exception {
        AgentPlan plan = new AgentPlan(agent);
        AgentPlan restored = MAPPER.readValue(MAPPER.writeValueAsString(plan), AgentPlan.class);
        VectorStoreSearchTool tool =
                (VectorStoreSearchTool)
                        restored.getResourceProviders()
                                .get(ResourceType.TOOL)
                                .get(toolName)
                                .provide(context);
        tool.open();
        return tool;
    }

    private static BaseVectorStore emptyStore() {
        BaseVectorStore store = mock(BaseVectorStore.class);
        when(store.query(any())).thenReturn(new VectorStoreQueryResult(List.of()));
        return store;
    }

    @Test
    void annotatedFieldToolSurvivesPlanJsonAndResolvesItsStore() throws Exception {
        BaseVectorStore store = emptyStore();
        ResourceContext context =
                TestResourceContexts.fromGetResource(
                        (name, type) ->
                                "knowledge_base".equals(name) && type == ResourceType.VECTOR_STORE
                                        ? store
                                        : null);

        VectorStoreSearchTool tool = restoreAndOpen(new FieldAgent(), "searchDocs", context);

        assertThat(tool.getName()).isEqualTo("searchDocs");
        assertThat(tool.getDescription()).isEqualTo("Search the docs.");
        assertThat(tool.getVectorStore()).isEqualTo("knowledge_base");
        assertThat(tool.getMaxResults()).isEqualTo(5);
        assertThat(tool.getResourceContext()).isSameAs(context);
        assertThat(tool.call(new ToolParameters(Map.of("query", "flink"))).getText())
                .isEqualTo("No relevant documents found.");
        verify(store).query(any());
    }

    @Test
    void programmaticToolSurvivesPlanJsonAndResolvesItsStore() throws Exception {
        BaseVectorStore store = emptyStore();
        ResourceContext context = TestResourceContexts.fromGetResource((name, type) -> store);

        VectorStoreSearchTool tool =
                restoreAndOpen(new ProgrammaticAgent(), "search_docs", context);

        assertThat(tool.getName()).isEqualTo("search_docs");
        assertThat(tool.getMaxResults()).isEqualTo(5);
        tool.call(new ToolParameters(Map.of("query", "flink")));
        verify(store).query(any());
    }
}
