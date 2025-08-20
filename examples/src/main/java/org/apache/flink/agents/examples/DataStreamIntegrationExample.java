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
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;

/**
 * Example demonstrating how to integrate Flink Agents with DataStream API.
 *
 * <p>This example shows how to:
 *
 * <ul>
 *   <li>Create a DataStream from a collection
 *   <li>Apply an agent to process the stream
 *   <li>Extract output as another DataStream
 *   <li>Execute the pipeline
 * </ul>
 */
public class DataStreamIntegrationExample {

    /** Simple data class for the example. */
    public static class ItemData {
        public final int id;
        public final String name;
        public final double value;
        public int visit_count;

        public ItemData(int id, String name, double value) {
            this.id = id;
            this.name = name;
            this.value = value;
            this.visit_count = 0;
        }

        @Override
        public String toString() {
            return String.format(
                    "ItemData{id=%d, name='%s', value=%.2f，visit_count=%d}",
                    id, name, value, visit_count);
        }
    }

    /** Key selector for extracting keys from ItemData. */
    public static class ItemKeySelector implements KeySelector<ItemData, Integer> {
        @Override
        public Integer getKey(ItemData item) {
            return item.id;
        }
    }

    public static void main(String[] args) throws Exception {
        // Create the execution environment
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.setParallelism(1);

        // Create input DataStream
        DataStream<ItemData> inputStream =
                env.fromElements(
                        new ItemData(1, "item1", 10.5),
                        new ItemData(2, "item2", 20.0),
                        new ItemData(3, "item3", 15.7),
                        new ItemData(1, "item1_updated", 12.3),
                        new ItemData(2, "item2_updated", 22.1),
                        new ItemData(1, "item1_updated_again", 15.3));

        // Create agents execution environment
        AgentsExecutionEnvironment agentsEnv =
                AgentsExecutionEnvironment.getExecutionEnvironment(env);

        // Apply agent to the DataStream
        DataStream<Object> outputStream =
                agentsEnv
                        .fromDataStream(inputStream, new ItemKeySelector())
                        .apply(new DataStreamAgent())
                        .toDataStream();

        // Print the results
        outputStream.print();

        // Execute the pipeline
        agentsEnv.execute();
    }
}
