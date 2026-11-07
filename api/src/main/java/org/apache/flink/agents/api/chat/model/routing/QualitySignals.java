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
package org.apache.flink.agents.api.chat.model.routing;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Factory for the quality signals an {@link Escalation} evaluates after each model round. The
 * structural checks are always on and need no declaration here.
 */
public final class QualitySignals {

    private QualitySignals() {}

    /**
     * A registered {@code DECISION_MODEL} judges the answer with one predicate question ("does this
     * answer satisfy the request?"); a {@code probability_true} below {@code minProbability}
     * escalates. The engine runs the call on its durable, metered path under the id {@code
     * escalate:<router>:<candidate>}, so a stored verdict is reused on replay. The reported
     * probability becomes the verdict's score. Decision-model probabilities are not calibrated
     * everywhere: choose the threshold from measurements on your own traffic.
     *
     * @param decisionModel the name of the registered decision model
     * @param minProbability the acceptance bound, in [0, 1]
     */
    public static Escalation.Signal decision(String decisionModel, double minProbability) {
        return decision(decisionModel, minProbability, null);
    }

    /**
     * Like {@link #decision(String, double)} with custom predicate instructions in place of the
     * built-in question.
     */
    public static Escalation.Signal decision(
            String decisionModel, double minProbability, String instructions) {
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put(
                Escalation.Signal.DECISION_MODEL_KEY,
                Objects.requireNonNull(decisionModel, "decisionModel"));
        arguments.put(Escalation.Signal.MIN_PROBABILITY_KEY, minProbability);
        if (instructions != null) {
            arguments.put(Escalation.Signal.INSTRUCTIONS_KEY, instructions);
        }
        Escalation.Signal signal =
                new Escalation.Signal(Escalation.Signal.TYPE_DECISION_MODEL, arguments);
        signal.validate("QualitySignals.decision");
        return signal;
    }

    /**
     * A user-defined {@link QualitySignal}, instantiated by the engine through its public
     * no-argument constructor. Its verdict is persisted as a durable record under {@code
     * escalate:<router>:<candidate>:<n>} and reused on replay.
     */
    public static Escalation.Signal custom(Class<? extends QualitySignal> signalClass) {
        return custom(Objects.requireNonNull(signalClass, "signalClass").getName(), Map.of());
    }

    /**
     * Like {@link #custom(Class)} with arguments the signal can read through its constructor taking
     * a {@code Map<String, Object>}; a class without that constructor ignores them.
     */
    public static Escalation.Signal custom(String signalClass, Map<String, Object> args) {
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put(
                Escalation.Signal.SIGNAL_CLASS_KEY,
                Objects.requireNonNull(signalClass, "signalClass"));
        if (args != null && !args.isEmpty()) {
            arguments.put(Escalation.Signal.ARGS_KEY, new LinkedHashMap<>(args));
        }
        Escalation.Signal signal = new Escalation.Signal(Escalation.Signal.TYPE_CUSTOM, arguments);
        signal.validate("QualitySignals.custom");
        return signal;
    }
}
