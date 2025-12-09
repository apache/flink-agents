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

package org.apache.flink.agents.integration.test;

import org.apache.flink.agents.api.AgentsExecutionEnvironment;
import org.apache.flink.api.java.functions.KeySelector;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.table.api.DataTypes;
import org.apache.flink.table.api.Schema;
import org.apache.flink.table.api.Table;
import org.apache.flink.table.api.bridge.java.StreamTableEnvironment;
import org.apache.flink.types.Row;
import org.apache.flink.util.CloseableIterator;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Example demonstrating how to integrate Flink Agents with Table API.
 *
 * <p>This example shows how to:
 *
 * <ul>
 *   <li>Create a Table from sample data
 *   <li>Apply an agent to process the table
 *   <li>Extract output as another Table
 *   <li>Execute the pipeline
 * </ul>
 */
public class FlinkIntegrationTest {

    /** Key selector for extracting keys from Row objects. */
    public static class RowKeySelector implements KeySelector<Object, Integer> {
        @Override
        public Integer getKey(Object value) {
            if (value instanceof Row) {
                Row row = (Row) value;
                return (Integer) row.getField(0); // Assuming first field is the ID
            }
            return 0;
        }
    }

    @Test
    public void testFromDataStreamToDataStream() throws Exception {
        // Create the execution environment
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.setParallelism(1);

        // Create input DataStream
        DataStream<FlinkIntegrationAgent.ItemData> inputStream =
                env.fromElements(
                        new FlinkIntegrationAgent.ItemData(1, "item1", 10.5),
                        new FlinkIntegrationAgent.ItemData(2, "item2", 20.0),
                        new FlinkIntegrationAgent.ItemData(3, "item3", 15.7),
                        new FlinkIntegrationAgent.ItemData(1, "item1_updated", 12.3),
                        new FlinkIntegrationAgent.ItemData(2, "item2_updated", 22.1),
                        new FlinkIntegrationAgent.ItemData(1, "item1_updated_again", 15.3));

        // Create agents execution environment
        AgentsExecutionEnvironment agentsEnv =
                AgentsExecutionEnvironment.getExecutionEnvironment(env);

        // Apply agent to the DataStream
        DataStream<Object> outputStream =
                agentsEnv
                        .fromDataStream(inputStream, new FlinkIntegrationAgent.ItemKeySelector())
                        .apply(new FlinkIntegrationAgent.DataStreamAgent())
                        .toDataStream();

        // Collect the results
        CloseableIterator<Object> results = outputStream.collectAsync();

        // Execute the pipeline
        agentsEnv.execute();

        checkResult(results, "test_from_datastream_to_datastream.txt");
    }

    @Test
    public void testFromTableToTable() throws Exception {
        // Create the execution environment
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.setParallelism(1);

        // Create the table environment
        StreamTableEnvironment tableEnv = StreamTableEnvironment.create(env);
        tableEnv.getConfig().set("table.exec.result.display.max-column-width", "100");

        // Create input table from sample data
        Table inputTable =
                tableEnv.fromValues(
                        DataTypes.ROW(
                                DataTypes.FIELD("id", DataTypes.INT()),
                                DataTypes.FIELD("name", DataTypes.STRING()),
                                DataTypes.FIELD("score", DataTypes.DOUBLE())),
                        Row.of(1, "Alice", 85.5),
                        Row.of(2, "Bob", 92.0),
                        Row.of(3, "Charlie", 78.3),
                        Row.of(1, "Alice", 87.2),
                        Row.of(2, "Bob", 94.1),
                        Row.of(1, "Alice", 90.3));

        // Create agents execution environment
        AgentsExecutionEnvironment agentsEnv =
                AgentsExecutionEnvironment.getExecutionEnvironment(env, tableEnv);

        // Define output schema
        Schema outputSchema = Schema.newBuilder().column("f0", DataTypes.STRING()).build();

        // Apply agent to the Table
        Table outputTable =
                agentsEnv
                        .fromTable(inputTable, new RowKeySelector())
                        .apply(new FlinkIntegrationAgent.TableAgent())
                        .toTable(outputSchema);

        // Collect the results in table format
        CloseableIterator<Row> results = outputTable.execute().collect();

        checkResult(results, "test_from_table_to_table.txt");
    }

    @Test
    public void testFromDataStreamToTable() throws Exception {
        // Create the execution environment
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.setParallelism(1);

        // Create input DataStream
        DataStream<FlinkIntegrationAgent.ItemData> inputStream =
                env.fromElements(
                        new FlinkIntegrationAgent.ItemData(1, "item1", 10.5),
                        new FlinkIntegrationAgent.ItemData(2, "item2", 20.0),
                        new FlinkIntegrationAgent.ItemData(3, "item3", 15.7),
                        new FlinkIntegrationAgent.ItemData(1, "item1_updated", 12.3),
                        new FlinkIntegrationAgent.ItemData(2, "item2_updated", 22.1),
                        new FlinkIntegrationAgent.ItemData(1, "item1_updated_again", 15.3));

        // Create agents execution environment
        AgentsExecutionEnvironment agentsEnv =
                AgentsExecutionEnvironment.getExecutionEnvironment(env);

        // Define output schema
        Schema outputSchema = Schema.newBuilder().column("f0", DataTypes.STRING()).build();

        // Apply agent to the Table
        Table outputTable =
                agentsEnv
                        .fromDataStream(inputStream, new FlinkIntegrationAgent.ItemKeySelector())
                        .apply(new FlinkIntegrationAgent.DataStreamAgent())
                        .toTable(outputSchema);

        // Collect the results in table format
        CloseableIterator<Row> results = outputTable.execute().collect();

        checkResult(results, "test_from_datastream_to_table.txt");
    }

    private void checkResult(CloseableIterator<?> results, String fileName) throws IOException {
        String path =
                Objects.requireNonNull(
                                getClass().getClassLoader().getResource("ground-truth/" + fileName))
                        .getPath();
        List<String> expected = Files.readAllLines(Path.of(path));
        expected.sort(Comparator.naturalOrder());

        List<String> actual = new ArrayList<>();
        while (results.hasNext()) {
            actual.add(results.next().toString());
        }
        actual.sort(Comparator.naturalOrder());

        Assertions.assertEquals(
                expected.size(), actual.size(), "Output messages count is not same as expected");
        for (int i = 0; i < expected.size(); i++) {
            Assertions.assertEquals(expected.get(i), actual.get(i));
        }
    }
}
