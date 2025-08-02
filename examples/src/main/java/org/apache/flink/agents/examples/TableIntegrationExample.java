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

package org.apache.flink.agents.examples;

import org.apache.flink.agents.api.AgentsExecutionEnvironment;
import org.apache.flink.api.java.functions.KeySelector;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.table.api.DataTypes;
import org.apache.flink.table.api.Schema;
import org.apache.flink.table.api.Table;
import org.apache.flink.table.api.bridge.java.StreamTableEnvironment;
import org.apache.flink.types.Row;

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
public class TableIntegrationExample {

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

    public static void main(String[] args) throws Exception {
        // Create the execution environment
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.setParallelism(1);

        // Create the table environment
        StreamTableEnvironment tableEnv = StreamTableEnvironment.create(env);

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
                        Row.of(2, "Bob", 94.1));

        // Create agents execution environment
        AgentsExecutionEnvironment agentsEnv =
                AgentsExecutionEnvironment.getExecutionEnvironment(env);

        // Define output schema
        Schema outputSchema = Schema.newBuilder().column("result", DataTypes.STRING()).build();

        // Apply agent to the Table
        Table outputTable =
                agentsEnv
                        .fromTable(inputTable, tableEnv, new RowKeySelector())
                        .apply(new SimpleAgent())
                        .toTable(outputSchema);

        // Print the results
        outputTable.execute().print();
    }
}
