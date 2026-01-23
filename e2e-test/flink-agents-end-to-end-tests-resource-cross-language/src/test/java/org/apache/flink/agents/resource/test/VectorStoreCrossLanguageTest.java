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

import org.apache.flink.agents.api.AgentsExecutionEnvironment;
import org.apache.flink.api.java.functions.KeySelector;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.datastream.DataStreamSource;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.util.CloseableIterator;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.util.Map;

import static org.apache.flink.agents.resource.test.CrossLanguageTestPreparationUtils.pullModel;
import static org.apache.flink.agents.resource.test.VectorStoreCrossLanguageAgent.OLLAMA_MODEL;

public class VectorStoreCrossLanguageTest {

    private final boolean ollamaReady;

    public VectorStoreCrossLanguageTest() throws IOException {
        ollamaReady = pullModel(OLLAMA_MODEL);
    }

    @ParameterizedTest
    @ValueSource(strings = {"JAVA", "PYTHON"})
    public void testVectorStoreIntegration(String embeddingType) throws Exception {
        System.setProperty("EMBEDDING_TYPE", embeddingType);
        Assumptions.assumeTrue(ollamaReady, "Ollama Server information is not provided");

        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.setParallelism(1);

        final DataStreamSource<String> inputStream = env.fromData("What is Apache Flink");

        final AgentsExecutionEnvironment agentEnv =
                AgentsExecutionEnvironment.getExecutionEnvironment(env);

        final DataStream<Object> outputStream =
                agentEnv.fromDataStream(
                                inputStream, (KeySelector<String, String>) value -> "orderKey")
                        .apply(new VectorStoreCrossLanguageAgent())
                        .toDataStream();

        final CloseableIterator<Object> results = outputStream.collectAsync();

        agentEnv.execute();

        checkResult(results);
    }

    @SuppressWarnings("unchecked")
    private void checkResult(CloseableIterator<Object> results) {
        Assertions.assertTrue(
                results.hasNext(), "No output received from VectorStoreIntegrationAgent");

        Object obj = results.next();
        Assertions.assertInstanceOf(Map.class, obj, "Output must be a Map");

        java.util.Map<String, Object> res = (java.util.Map<String, Object>) obj;
        Assertions.assertEquals("PASSED", res.get("test_status"));

        Object count = res.get("retrieved_count");
        Assertions.assertNotNull(count, "retrieved_count must exist");
        if (count instanceof Number) {
            Assertions.assertTrue(((Number) count).intValue() >= 1, "retrieved_count must be >= 1");
        }

        Object preview = res.get("first_doc_preview");
        Assertions.assertTrue(
                preview instanceof String && !((String) preview).trim().isEmpty(),
                "first_doc_preview must be a non-empty string");

        Object firstId = res.get("first_doc_id");
        if (firstId != null) {
            Assertions.assertTrue(
                    firstId instanceof String && !((String) firstId).trim().isEmpty(),
                    "first_doc_id when present must be a non-empty string");
        }
    }
}
