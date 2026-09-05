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

package org.apache.flink.agents.runtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.agents.plan.AgentPlan;
import org.apache.flink.agents.runtime.operator.ActionExecutionOperatorFactory;
import org.apache.flink.api.common.RuntimeExecutionMode;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.java.functions.KeySelector;
import org.apache.flink.api.java.typeutils.RowTypeInfo;
import org.apache.flink.configuration.ExecutionOptions;
import org.apache.flink.configuration.ReadableConfig;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.datastream.KeyedStream;
import org.apache.flink.streaming.api.typeinfo.python.PickledByteArrayTypeInfo;
import org.apache.flink.types.Row;

import static org.apache.flink.util.Preconditions.checkArgument;
import static org.apache.flink.util.Preconditions.checkState;

/** A utility class that bridges Flink DataStream/SQL with the Flink Agents agent. */
public class CompileUtils {

    private static final int PYTHON_KEY_FIELD_INDEX = 0;
    private static final int PYTHON_VALUE_FIELD_INDEX = 1;

    // ============================ invoke by python ====================================
    public static DataStream<byte[]> connectToAgent(
            KeyedStream<Row, Row> inputDataStream, String agentPlanJson)
            throws JsonProcessingException {
        TypeInformation<?> inputType = inputDataStream.getType();
        checkArgument(
                isPickledPythonFieldType(inputType, PYTHON_VALUE_FIELD_INDEX),
                "Flink Agents only supports PyFlink input values serialized with "
                        + "PickledByteArrayTypeInfo. Convert raw byte-array inputs with a Python "
                        + "operator using the default pickle output type before connecting them "
                        + "to Flink Agents, but got %s",
                inputType);

        // deserialize agent plan json.
        AgentPlan agentPlan = new ObjectMapper().readValue(agentPlanJson, AgentPlan.class);
        return connectToAgent(
                inputDataStream,
                agentPlan,
                TypeInformation.of(byte[].class),
                false,
                isPickledPythonFieldType(inputDataStream.getKeyType(), PYTHON_KEY_FIELD_INDEX));
    }

    // ============================ invoke by java ====================================
    public static <IN, K> DataStream<Object> connectToAgent(
            DataStream<IN> inputStream, KeySelector<IN, K> keySelector, AgentPlan agentPlan) {
        return connectToAgent(inputStream.keyBy(keySelector), agentPlan);
    }

    public static <IN, K> DataStream<Object> connectToAgent(
            KeyedStream<IN, K> keyedInputStream, AgentPlan agentPlan) {
        return connectToAgent(
                keyedInputStream, agentPlan, TypeInformation.of(Object.class), true, false);
    }

    // ============================ basic ====================================
    /**
     * Connects the given KeyedStream to the Flink Agents agent.
     *
     * <p>This method accepts a keyed DataStream and applies the specified agent plan to it. The
     * source of the input stream determines the data format: Java streams provide Objects, while
     * Python streams use serialized byte arrays.
     *
     * @param keyedInputStream The input keyed DataStream.
     * @param agentPlan The agent plan to be executed.
     * @param inputIsJava A flag indicating whether the input stream originates from Java. - If
     *     true, input and output types are Java Objects. - If false, input and output types are
     *     byte[].
     * @param <K> The type of the key used in the keyed DataStream.
     * @param <IN> The type of the input data (Object or byte[]).
     * @param <OUT> The type of the output data (Object or byte[]).
     * @return The processed DataStream as the result of the agent.
     */
    private static <K, IN, OUT> DataStream<OUT> connectToAgent(
            KeyedStream<IN, K> keyedInputStream,
            AgentPlan agentPlan,
            TypeInformation<OUT> outTypeInformation,
            boolean inputIsJava,
            boolean pythonKeyIsPickled) {
        checkBatchStateBackendCompatibility(keyedInputStream);
        return (DataStream<OUT>)
                keyedInputStream
                        .transform(
                                "action-execute-operator",
                                outTypeInformation,
                                new ActionExecutionOperatorFactory(
                                        agentPlan, inputIsJava, pythonKeyIsPickled))
                        .setParallelism(keyedInputStream.getParallelism());
    }

    /**
     * Rejects a job configuration known to silently drop records: explicit {@code
     * RuntimeExecutionMode.BATCH} together with Flink's batch-specific keyed-state backend (enabled
     * by default in that mode).
     *
     * <p>{@link ActionExecutionOperatorFactory}'s operator keeps pending action tasks in keyed
     * state across mailbox continuations that can span multiple keys of the same subtask. Flink's
     * batch keyed-state backend assumes a key is fully processed before moving to the next one and
     * clears keyed state on every key switch, so a continuation for one key can be silently
     * discarded by the operator moving on to process another key first. The job still reports
     * {@code FINISHED}; only the affected records go missing. See
     * https://github.com/apache/flink-agents/issues/939.
     *
     * <p>This only catches an explicitly configured {@code BATCH} runtime mode. {@code
     * RuntimeExecutionMode.AUTOMATIC} (the default) that later resolves to batch execution because
     * every source happens to be bounded is not detected here, since that resolution has not
     * happened yet at graph-construction time.
     */
    private static void checkBatchStateBackendCompatibility(KeyedStream<?, ?> keyedInputStream) {
        ReadableConfig configuration =
                keyedInputStream.getExecutionEnvironment().getConfiguration();
        boolean isExplicitBatchMode =
                configuration.get(ExecutionOptions.RUNTIME_MODE) == RuntimeExecutionMode.BATCH;
        boolean usesBatchStateBackend = configuration.get(ExecutionOptions.USE_BATCH_STATE_BACKEND);
        checkState(
                !isExplicitBatchMode || !usesBatchStateBackend,
                "Flink Agents does not support RuntimeExecutionMode.BATCH with Flink's batch"
                        + " keyed-state backend (execution.batch-state-backend.enabled, enabled by"
                        + " default in BATCH mode): pending action-task state can be silently"
                        + " discarded when the operator processes another key before a key's"
                        + " workflow finishes, causing records to be dropped without any error."
                        + " Either run this job with RuntimeExecutionMode.STREAMING, or set"
                        + " execution.batch-state-backend.enabled to false. See"
                        + " https://github.com/apache/flink-agents/issues/939 for details.");
    }

    /** Returns whether a PyFlink Row field uses its default pickle representation. */
    static boolean isPickledPythonFieldType(TypeInformation<?> typeInformation, int fieldIndex) {
        checkArgument(fieldIndex >= 0, "Field index must not be negative, but got %s", fieldIndex);
        checkArgument(
                typeInformation instanceof RowTypeInfo,
                "Expected PyFlink type to be a RowTypeInfo, but got %s",
                typeInformation);
        RowTypeInfo rowType = (RowTypeInfo) typeInformation;
        int expectedArity = fieldIndex + 1;
        checkArgument(
                rowType.getArity() == expectedArity,
                "Expected PyFlink type to contain %s fields, but got arity %s",
                expectedArity,
                rowType.getArity());
        TypeInformation<?> fieldType = rowType.getTypeAt(fieldIndex);
        return fieldType instanceof PickledByteArrayTypeInfo;
    }
}
