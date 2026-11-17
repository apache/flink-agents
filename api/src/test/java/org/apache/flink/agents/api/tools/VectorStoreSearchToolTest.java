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

package org.apache.flink.agents.api.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.agents.api.prompt.Prompt;
import org.apache.flink.agents.api.resource.ResourceContext;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.api.vectorstores.BaseVectorStore;
import org.apache.flink.agents.api.vectorstores.Document;
import org.apache.flink.agents.api.vectorstores.VectorStoreQuery;
import org.apache.flink.agents.api.vectorstores.VectorStoreQueryResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Tests for {@link VectorStoreSearchTool}. */
class VectorStoreSearchToolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ResourceContext context;
    private BaseVectorStore store;

    @BeforeEach
    void setUp() throws Exception {
        context = mock(ResourceContext.class);
        store = mock(BaseVectorStore.class);
        when(context.getResource("knowledge_base", ResourceType.VECTOR_STORE)).thenReturn(store);
        when(store.query(any())).thenReturn(new VectorStoreQueryResult(List.of()));
    }

    private static VectorStoreSearchTool.Builder baseBuilder() {
        return VectorStoreSearchTool.builder()
                .name("search_docs")
                .description("Search the docs.")
                .vectorStore("knowledge_base");
    }

    private VectorStoreSearchTool openedTool(VectorStoreSearchTool tool) throws Exception {
        tool.setResourceContext(context);
        tool.open();
        return tool;
    }

    private static ToolParameters query(Object query) {
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("query", query);
        return new ToolParameters(parameters);
    }

    private void returnDocuments(Document... documents) {
        when(store.query(any())).thenReturn(new VectorStoreQueryResult(List.of(documents)));
    }

    @Test
    void metadataExposesOnlyQueryArgument() throws Exception {
        VectorStoreSearchTool tool = baseBuilder().build();

        assertThat(tool.getName()).isEqualTo("search_docs");
        assertThat(tool.getDescription()).isEqualTo("Search the docs.");
        assertThat(tool.getToolType()).isEqualTo(ToolType.FUNCTION);
        JsonNode schema = MAPPER.readTree(tool.getMetadata().getInputSchema());
        assertThat(schema.get("type").asText()).isEqualTo("object");
        assertThat(schema.get("properties").fieldNames()).toIterable().containsExactly("query");
        assertThat(schema.at("/properties/query/type").asText()).isEqualTo("string");
        assertThat(schema.get("required")).hasSize(1);
        assertThat(schema.get("required").get(0).asText()).isEqualTo("query");
    }

    @Test
    void callQueriesWithQueryTextAndMaxResults() throws Exception {
        VectorStoreSearchTool tool = openedTool(baseBuilder().maxResults(5).build());

        tool.call(query("what is flink"));

        ArgumentCaptor<VectorStoreQuery> captor = ArgumentCaptor.forClass(VectorStoreQuery.class);
        verify(store).query(captor.capture());
        assertThat(captor.getValue().getQueryText()).isEqualTo("what is flink");
        assertThat(captor.getValue().getLimit()).isEqualTo(5);
    }

    @Test
    void maxResultsDefaultsToThree() {
        assertThat(baseBuilder().build().getMaxResults()).isEqualTo(3);
    }

    @Test
    void rejectsInvalidArguments() {
        assertThatThrownBy(() -> baseBuilder().name(null).build())
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("name");
        assertThatThrownBy(() -> baseBuilder().description(null).build())
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("description");
        assertThatThrownBy(() -> baseBuilder().vectorStore(null).build())
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("vectorStore");
        assertThatThrownBy(() -> baseBuilder().maxResults(0).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxResults");
        assertThatThrownBy(() -> baseBuilder().maxResults(-1).build())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void callRejectsMissingOrNonStringQuery() throws Exception {
        VectorStoreSearchTool tool = openedTool(baseBuilder().build());

        assertThatThrownBy(() -> tool.call(new ToolParameters()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("query");
        assertThatThrownBy(() -> tool.call(query(42)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("query");
    }

    @Test
    void openResolvesVectorStoreOnceByName() throws Exception {
        VectorStoreSearchTool tool = openedTool(baseBuilder().build());

        tool.call(query("a"));
        tool.call(query("b"));

        verify(context, times(1)).getResource("knowledge_base", ResourceType.VECTOR_STORE);
        verify(store, times(2)).query(any());
    }

    @Test
    void callBeforeOpenThrows() {
        VectorStoreSearchTool tool = baseBuilder().build();

        assertThatThrownBy(() -> tool.call(query("a")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("knowledge_base");
    }

    @Test
    void openWithoutResourceContextThrows() {
        VectorStoreSearchTool tool = baseBuilder().build();

        assertThatThrownBy(tool::open)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ResourceContext");
    }

    @Test
    void openRejectsResourceThatIsNotAVectorStore() throws Exception {
        when(context.getResource("knowledge_base", ResourceType.VECTOR_STORE))
                .thenReturn(Prompt.fromText("not a store"));
        VectorStoreSearchTool tool = baseBuilder().build();
        tool.setResourceContext(context);

        assertThatThrownBy(tool::open)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("knowledge_base");
    }

    @Test
    void openPropagatesMissingVectorStore() throws Exception {
        IllegalArgumentException missing = new IllegalArgumentException("Resource not found");
        when(context.getResource("knowledge_base", ResourceType.VECTOR_STORE)).thenThrow(missing);
        VectorStoreSearchTool tool = baseBuilder().build();
        tool.setResourceContext(context);

        assertThatThrownBy(tool::open).isSameAs(missing);
    }

    @Test
    void callReturnsContentAndMetadataOnly() throws Exception {
        returnDocuments(
                new Document("first", Map.of("source", "a.md"), "id-1", new float[] {1f}, 0.9f),
                new Document("second", Map.of("source", "b.md"), "id-2", new float[] {2f}, 0.8f));
        VectorStoreSearchTool tool = openedTool(baseBuilder().build());

        ToolResponse response = tool.call(query("q"));

        assertThat(response.isSuccess()).isTrue();
        JsonNode results = MAPPER.readTree(response.getText());
        assertThat(results).hasSize(2);
        assertThat(results.get(0).fieldNames()).toIterable().containsExactly("content", "metadata");
        assertThat(results.get(0).get("content").asText()).isEqualTo("first");
        assertThat(results.get(0).at("/metadata/source").asText()).isEqualTo("a.md");
        assertThat(results.get(1).get("content").asText()).isEqualTo("second");
    }

    @Test
    void callReturnsMessageWhenNothingMatches() throws Exception {
        VectorStoreSearchTool tool = openedTool(baseBuilder().build());

        ToolResponse response = tool.call(query("q"));

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getText()).isEqualTo("No relevant documents found.");
    }

    @Test
    void callWritesUnusualMetadataAsValidJson() throws Exception {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("local_date", LocalDate.of(2024, 1, 2));
        metadata.put("instant", Instant.parse("2024-01-02T03:04:05Z"));
        metadata.put("date", new Date(0L));
        metadata.put("bytes", new byte[] {1, 2, 3});
        metadata.put("nan", Double.NaN);
        metadata.put("empty", new Object());
        metadata.put("nested", Map.of("pages", List.of(1, 2)));
        returnDocuments(new Document("doc", metadata, "id"));
        VectorStoreSearchTool tool = openedTool(baseBuilder().build());

        JsonNode converted =
                MAPPER.readTree(tool.call(query("q")).getText()).get(0).get("metadata");

        assertThat(converted.get("local_date").asText()).isEqualTo("2024-01-02");
        assertThat(converted.get("instant").asText()).isEqualTo("2024-01-02T03:04:05Z");
        assertThat(converted.get("date").asText()).startsWith("1970-01-01T00:00:00");
        assertThat(converted.get("bytes").asText()).isEqualTo("AQID");
        assertThat(converted.get("nan").asText()).isEqualTo("NaN");
        assertThat(converted.get("empty").isObject()).isTrue();
        assertThat(converted.at("/nested/pages").toString()).isEqualTo("[1,2]");
    }

    @Test
    void callPropagatesVectorStoreErrors() throws Exception {
        RuntimeException failure = new RuntimeException("store is down");
        when(store.query(any())).thenThrow(failure);
        VectorStoreSearchTool tool = openedTool(baseBuilder().build());

        assertThatThrownBy(() -> tool.call(query("q"))).isSameAs(failure);
    }

    @Test
    void survivesJsonRoundTrip() throws Exception {
        VectorStoreSearchTool tool = baseBuilder().maxResults(5).build();

        String json = MAPPER.writeValueAsString(tool);
        VectorStoreSearchTool restored = MAPPER.readValue(json, VectorStoreSearchTool.class);

        assertThat(MAPPER.readTree(json).fieldNames())
                .toIterable()
                .containsExactlyInAnyOrder("metadata", "vectorStore", "maxResults");
        assertThat(restored.getMetadata()).isEqualTo(tool.getMetadata());
        assertThat(restored.getVectorStore()).isEqualTo("knowledge_base");
        assertThat(restored.getMaxResults()).isEqualTo(5);
    }
}
