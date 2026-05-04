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
package org.apache.flink.agents.resource.test;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.agents.api.AgentsExecutionEnvironment;
import org.apache.flink.agents.api.configuration.AgentConfigOptions;
import org.apache.flink.agents.api.memory.LongTermMemoryOptions;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.util.CloseableIterator;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.apache.flink.agents.resource.test.CrossLanguageTestPreparationUtils.pullModel;
import static org.apache.flink.agents.resource.test.Mem0LongTermMemoryAgent.OLLAMA_EMBEDDING_MODEL;

/**
 * End-to-end test for {@link org.apache.flink.agents.runtime.memory.Mem0LongTermMemory}, mirroring
 * the Python {@code long_term_memory_test.py}. Streams four facts (alice/bob interleaved) through
 * {@link Mem0LongTermMemoryAgent}, exercising the full cross-language path: Java agent → Java
 * Mem0LongTermMemory wrapper → Python mem0 instance → mem0 adapter calling back into Java for chat
 * / embedding / vector store work.
 *
 * <p>Skipped when any prerequisite is missing:
 *
 * <ul>
 *   <li>Ollama daemon serving {@code nomic-embed-text} (the embedding model)
 *   <li>{@code ACTION_API_KEY} env var (and optionally {@code ACTION_BASE_URL}) for the
 *       OpenAI-compatible chat model — mirrors the Python e2e test's setup
 *   <li>{@code python} on PATH with {@code mem0ai} and {@code flink_agents} installed
 *   <li>Elasticsearch reachable via the {@code ES_HOST} env var
 * </ul>
 */
public class Mem0LongTermMemoryTest {

    private final boolean embeddingReady;
    private final boolean pythonReady;
    private final boolean esConfigured;
    private final boolean apiKeySet;

    public Mem0LongTermMemoryTest() throws IOException {
        embeddingReady = pullModel(OLLAMA_EMBEDDING_MODEL);
        pythonReady = isPythonAvailable();
        esConfigured = System.getenv("ES_HOST") != null;
        apiKeySet = System.getenv("ACTION_API_KEY") != null;
    }

    @Test
    @Disabled("Using mem0 in java depends on the pemja fix.")
    public void testMem0LongTermMemory() throws Exception {
        Assumptions.assumeTrue(
                embeddingReady,
                "Ollama is not reachable or the embedding model could not be pulled");
        Assumptions.assumeTrue(
                pythonReady,
                "`python` executable not found on PATH; this test requires Python with mem0ai installed");
        Assumptions.assumeTrue(esConfigured, "Elasticsearch env var (ES_HOST) is not set");
        Assumptions.assumeTrue(
                apiKeySet,
                "ACTION_API_KEY env var is not set; required for the OpenAI-compatible chat model");

        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.setParallelism(1);

        DataStream<Mem0LongTermMemoryAgent.ItemData> inputStream =
                env.fromElements(
                        new Mem0LongTermMemoryAgent.ItemData(
                                "alice", "My favorite fruit is watermelon."),
                        new Mem0LongTermMemoryAgent.ItemData("bob", "I like swimming."),
                        new Mem0LongTermMemoryAgent.ItemData(
                                "bob", "I'm a vegetarian and allergic to nuts."),
                        new Mem0LongTermMemoryAgent.ItemData(
                                "alice", "My favorite fruit is bananas."));

        AgentsExecutionEnvironment agentsEnv =
                AgentsExecutionEnvironment.getExecutionEnvironment(env);
        agentsEnv.getConfig().set(AgentConfigOptions.JOB_IDENTIFIER, "LTM_TEST_JOB");
        agentsEnv.getConfig().set(LongTermMemoryOptions.Mem0.CHAT_MODEL_SETUP, "openaiQwen3");
        agentsEnv
                .getConfig()
                .set(LongTermMemoryOptions.Mem0.EMBEDDING_MODEL_SETUP, "ollamaNomicEmbedText");
        agentsEnv.getConfig().set(LongTermMemoryOptions.Mem0.VECTOR_STORE, "esLtmStore");

        DataStream<Object> outputStream =
                agentsEnv
                        .fromDataStream(
                                inputStream, new Mem0LongTermMemoryAgent.ItemDataKeySelector())
                        .apply(new Mem0LongTermMemoryAgent())
                        .toDataStream();

        CloseableIterator<Object> results = outputStream.collectAsync();
        agentsEnv.execute();

        checkResult(results);
    }

    private static boolean isPythonAvailable() {
        try {
            Process p = new ProcessBuilder("python", "--version").start();
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private void checkResult(CloseableIterator<Object> results) throws Exception {
        Map<String, Map<String, Object>> records = new HashMap<>();
        for (int i = 0; i < 4; i++) {
            Assertions.assertTrue(
                    results.hasNext(), "Expected 4 records, only got " + records.size());
            Map<String, Object> record = (Map<String, Object>) results.next();
            String name = (String) record.get("name");
            int count = ((Number) record.get("count")).intValue();
            records.put(name + "." + count, record);
        }
        results.close();

        // alice's second pass must contain the items list, with mem0 having merged
        // the watermelon/banana facts into a single entry that mentions bananas.
        Map<String, Object> aliceTwo = records.get("alice.2");
        Assertions.assertNotNull(aliceTwo, "Missing alice.2 record");
        String itemsJson = (String) aliceTwo.get("items_json");
        Assertions.assertNotNull(itemsJson, "alice.2 record must carry the items_json payload");
        List<Map<String, Object>> items =
                new ObjectMapper()
                        .readValue(itemsJson, new TypeReference<List<Map<String, Object>>>() {});
        Assertions.assertEquals(1, items.size(), "Expected mem0 to merge alice's facts into one");

        Map<String, Object> item = items.get(0);
        String value = (String) item.get("value");
        Assertions.assertTrue(
                value.toLowerCase().contains("banana"),
                "alice's surviving item should reflect the bananas update, was: " + value);

        String createdAt = (String) item.get("created_at");
        String updatedAt = (String) item.get("updated_at");
        Assertions.assertNotNull(createdAt, "created_at must be populated");
        Assertions.assertNotNull(updatedAt, "updated_at must be populated");
        // The agent serialises these from MemorySetItem's LocalDateTime fields, so they have
        // no trailing 'Z'/offset and must be parsed as LocalDateTime, not Instant.
        Assertions.assertTrue(
                LocalDateTime.parse(createdAt).isBefore(LocalDateTime.parse(updatedAt)),
                "updated_at should be strictly after created_at when mem0 merged the facts");

        // Async add must not block other keys: alice.1 starts before bob.1 finishes.
        Map<String, Object> aliceOne = records.get("alice.1");
        Map<String, Object> bobOne = records.get("bob.1");
        Assertions.assertNotNull(aliceOne, "Missing alice.1 record");
        Assertions.assertNotNull(bobOne, "Missing bob.1 record");
        Assertions.assertTrue(
                Instant.parse((String) aliceOne.get("timestamp_before_add"))
                        .isBefore(Instant.parse((String) bobOne.get("timestamp_after_add"))),
                "alice.1 should start its add before bob.1 finishes (async non-blocking)");
    }
}
