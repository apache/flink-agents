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

import javax.annotation.Nullable;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The quality-triggered escalation policy of a {@link ModelRouter}: explicit tiers, cheapest first,
 * and the signals that send a request up a tier.
 *
 * <p>Fallback handles invocation failures; escalation handles answers that arrived but scored
 * badly. After each model round of a request that is on a tier below the last one, the engine runs
 * the structural checks (an empty answer with no tool call, a call to a tool the candidate does not
 * have, arguments that fail the tool's input schema, the same tool call repeated with identical
 * arguments in consecutive rounds) and then the declared signals, in order. The first escalating
 * verdict discards that round's answer before any of its tool calls is dispatched and re-sends the
 * conversation so far to the next tier, where the request then stays. The last tier always accepts
 * on quality; if it fails after its retries, fallback (when enabled) skips the candidates that
 * already answered this request, and with none left the request fails.
 *
 * <pre>{@code
 * ModelRouter.of("small", "big")
 *         .escalation(
 *                 Escalation.tiers("small", "big")
 *                         .when(QualitySignals.decision("verifier", 0.7)))
 *         .build();
 * }</pre>
 *
 * <p>Declared tiers must be candidates of the router; candidates outside the tiers stay fallback
 * targets only. A router with tiers needs no {@code strategy(...)}: every request starts on the
 * first tier, which also becomes the default model when none is set. A strategy may still pick the
 * starting tier.
 */
public final class Escalation implements Serializable {
    private static final long serialVersionUID = 1L;

    /** Router argument: the tiers, cheapest first. */
    public static final String TIERS_KEY = "escalation_tiers";

    /** Router argument: the declared signals, each a map (see {@link Signal}). */
    public static final String SIGNALS_KEY = "escalation_signals";

    /** Router argument: the maximum number of hops for one request. */
    public static final String MAX_ESCALATIONS_KEY = "escalation_max";

    private final List<String> tiers;
    private final List<Signal> signals;
    private final int maxEscalations;

    private Escalation(List<String> tiers, List<Signal> signals, int maxEscalations) {
        this.tiers = Collections.unmodifiableList(new ArrayList<>(tiers));
        this.signals = Collections.unmodifiableList(new ArrayList<>(signals));
        this.maxEscalations = maxEscalations;
        validate(this.tiers, this.maxEscalations);
    }

    /**
     * Start a policy with the given tiers, cheapest first. At least two distinct, non-blank names;
     * the tier count minus one bounds the hops unless {@link #maxEscalations(int)} caps it lower.
     */
    public static Escalation tiers(String... tiers) {
        return new Escalation(
                tiers == null ? List.of() : List.of(tiers), List.of(), Integer.MAX_VALUE);
    }

    /** Add a signal, evaluated after the structural checks and the signals added before it. */
    public Escalation when(Signal signal) {
        List<Signal> next = new ArrayList<>(this.signals);
        next.add(Objects.requireNonNull(signal, "signal"));
        return new Escalation(this.tiers, next, this.maxEscalations);
    }

    /** Cap the number of hops for one request below the tier count minus one. */
    public Escalation maxEscalations(int maxEscalations) {
        return new Escalation(this.tiers, this.signals, maxEscalations);
    }

    public List<String> getTiers() {
        return tiers;
    }

    public List<Signal> getSignals() {
        return signals;
    }

    /** The effective hop limit: the declared cap, bounded by the tier count minus one. */
    public int getMaxEscalations() {
        return Math.min(maxEscalations, tiers.size() - 1);
    }

    /** Position of {@code candidate} in the tiers, or -1 when it is not a tier. */
    public int tierIndex(String candidate) {
        return tiers.indexOf(candidate);
    }

    /** The tier after {@code candidate}, if any. */
    public Optional<String> nextTier(String candidate) {
        int index = tiers.indexOf(candidate);
        if (index < 0 || index + 1 >= tiers.size()) {
            return Optional.empty();
        }
        return Optional.of(tiers.get(index + 1));
    }

    /** Writes this policy into a router's descriptor arguments. */
    public void toArguments(Map<String, Object> arguments) {
        arguments.put(TIERS_KEY, new ArrayList<>(tiers));
        if (!signals.isEmpty()) {
            List<Map<String, Object>> declared = new ArrayList<>();
            for (Signal signal : signals) {
                declared.add(signal.toMap());
            }
            arguments.put(SIGNALS_KEY, declared);
        }
        if (maxEscalations != Integer.MAX_VALUE) {
            arguments.put(MAX_ESCALATIONS_KEY, maxEscalations);
        }
    }

    /**
     * Reads a policy from a router's descriptor arguments; empty when none is declared. A
     * hand-built or deserialized descriptor may carry any JSON type, so every field is checked and
     * diagnosed with the router named in {@code subject}.
     *
     * @throws IllegalArgumentException when the declaration is malformed
     */
    public static Optional<Escalation> fromArguments(
            String subject, Map<String, Object> arguments) {
        Object rawTiers = arguments.get(TIERS_KEY);
        if (rawTiers == null) {
            if (arguments.containsKey(SIGNALS_KEY) || arguments.containsKey(MAX_ESCALATIONS_KEY)) {
                throw new IllegalArgumentException(
                        subject
                                + " declares escalation signals or a cap without '"
                                + TIERS_KEY
                                + "'.");
            }
            return Optional.empty();
        }
        if (!(rawTiers instanceof List)) {
            throw new IllegalArgumentException(
                    subject
                            + " declares '"
                            + TIERS_KEY
                            + "' as "
                            + rawTiers.getClass().getSimpleName()
                            + "; expected a list of names.");
        }
        List<String> tiers = new ArrayList<>();
        for (Object tier : (List<?>) rawTiers) {
            if (!(tier instanceof String)) {
                throw new IllegalArgumentException(
                        subject + " has a non-String escalation tier: " + tier);
            }
            tiers.add((String) tier);
        }
        List<Signal> signals = new ArrayList<>();
        Object rawSignals = arguments.get(SIGNALS_KEY);
        if (rawSignals != null) {
            if (!(rawSignals instanceof List)) {
                throw new IllegalArgumentException(
                        subject
                                + " declares '"
                                + SIGNALS_KEY
                                + "' as "
                                + rawSignals.getClass().getSimpleName()
                                + "; expected a list.");
            }
            for (Object declared : (List<?>) rawSignals) {
                if (!(declared instanceof Map)) {
                    throw new IllegalArgumentException(
                            subject + " has an escalation signal that is not a map: " + declared);
                }
                signals.add(Signal.fromMap(subject, (Map<?, ?>) declared));
            }
        }
        int maxEscalations = Integer.MAX_VALUE;
        Object rawMax = arguments.get(MAX_ESCALATIONS_KEY);
        if (rawMax != null) {
            if (!(rawMax instanceof Number) || ((Number) rawMax).longValue() < 1) {
                throw new IllegalArgumentException(
                        subject
                                + " declares '"
                                + MAX_ESCALATIONS_KEY
                                + "' as "
                                + rawMax
                                + "; expected a positive integer.");
            }
            maxEscalations = (int) Math.min(Integer.MAX_VALUE, ((Number) rawMax).longValue());
        }
        try {
            return Optional.of(new Escalation(tiers, signals, maxEscalations));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(subject + ": " + e.getMessage(), e);
        }
    }

    /** Every tier must be a candidate of the router. */
    public void requireTiersAreCandidates(String subject, List<String> candidates) {
        for (String tier : tiers) {
            if (!candidates.contains(tier)) {
                throw new IllegalArgumentException(
                        String.format(
                                "%s escalation tier '%s' is not one of the candidates %s.",
                                subject, tier, candidates));
            }
        }
    }

    private static void validate(List<String> tiers, int maxEscalations) {
        if (tiers.size() < 2) {
            throw new IllegalArgumentException(
                    "Escalation needs at least two tiers, cheapest first (got " + tiers + ").");
        }
        Set<String> seen = new LinkedHashSet<>();
        for (String tier : tiers) {
            if (tier == null || tier.isBlank()) {
                throw new IllegalArgumentException("Escalation tiers must be non-blank names.");
            }
            if (!seen.add(tier)) {
                throw new IllegalArgumentException(
                        "Escalation tier '" + tier + "' is declared more than once.");
            }
        }
        if (maxEscalations < 1) {
            throw new IllegalArgumentException("maxEscalations must be at least 1.");
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Escalation)) {
            return false;
        }
        Escalation other = (Escalation) o;
        return maxEscalations == other.maxEscalations
                && tiers.equals(other.tiers)
                && signals.equals(other.signals);
    }

    @Override
    public int hashCode() {
        return Objects.hash(tiers, signals, maxEscalations);
    }

    @Override
    public String toString() {
        return "Escalation{tiers="
                + tiers
                + ", signals="
                + signals
                + ", max="
                + getMaxEscalations()
                + "}";
    }

    /**
     * A declared quality signal: the built-in decision-model signal or a user class. Declarations
     * are plain maps in the router descriptor so they serialize like every other resource argument.
     */
    public static final class Signal implements Serializable {
        private static final long serialVersionUID = 1L;

        public static final String TYPE_KEY = "type";
        public static final String TYPE_DECISION_MODEL = "decision_model";
        public static final String TYPE_CUSTOM = "custom";
        public static final String DECISION_MODEL_KEY = "decision_model";
        public static final String MIN_PROBABILITY_KEY = "min_probability";
        public static final String INSTRUCTIONS_KEY = "instructions";
        public static final String SIGNAL_CLASS_KEY = "signal_class";
        public static final String ARGS_KEY = "args";

        private final String type;
        private final Map<String, Object> arguments;

        Signal(String type, Map<String, Object> arguments) {
            this.type = Objects.requireNonNull(type, "type");
            this.arguments = Collections.unmodifiableMap(new LinkedHashMap<>(arguments));
        }

        public String getType() {
            return type;
        }

        public Map<String, Object> getArguments() {
            return arguments;
        }

        /** The decision model name of a decision-model signal. */
        public String decisionModel() {
            return (String) arguments.get(DECISION_MODEL_KEY);
        }

        /** The threshold of a decision-model signal. */
        public double minProbability() {
            return ((Number) arguments.get(MIN_PROBABILITY_KEY)).doubleValue();
        }

        /** The predicate instructions of a decision-model signal, or null for the default. */
        @Nullable
        public String instructions() {
            return (String) arguments.get(INSTRUCTIONS_KEY);
        }

        /** The class name of a custom signal. */
        public String signalClass() {
            return (String) arguments.get(SIGNAL_CLASS_KEY);
        }

        /** The arguments of a custom signal, never null. */
        @SuppressWarnings("unchecked")
        public Map<String, Object> signalArgs() {
            Object args = arguments.get(ARGS_KEY);
            return args instanceof Map ? (Map<String, Object>) args : Collections.emptyMap();
        }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put(TYPE_KEY, type);
            map.putAll(arguments);
            return map;
        }

        static Signal fromMap(String subject, Map<?, ?> declared) {
            Object type = declared.get(TYPE_KEY);
            if (!(type instanceof String)) {
                throw new IllegalArgumentException(
                        subject + " has an escalation signal without a '" + TYPE_KEY + "'.");
            }
            Map<String, Object> arguments = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : declared.entrySet()) {
                if (!(entry.getKey() instanceof String)) {
                    throw new IllegalArgumentException(
                            subject + " has an escalation signal with a non-String key.");
                }
                if (!TYPE_KEY.equals(entry.getKey())) {
                    arguments.put((String) entry.getKey(), entry.getValue());
                }
            }
            Signal signal = new Signal((String) type, arguments);
            signal.validate(subject);
            return signal;
        }

        void validate(String subject) {
            switch (type) {
                case TYPE_DECISION_MODEL:
                    Object decisionModel = arguments.get(DECISION_MODEL_KEY);
                    if (!(decisionModel instanceof String) || ((String) decisionModel).isBlank()) {
                        throw new IllegalArgumentException(
                                subject
                                        + " decision-model signal requires a non-empty '"
                                        + DECISION_MODEL_KEY
                                        + "'.");
                    }
                    Object min = arguments.get(MIN_PROBABILITY_KEY);
                    if (!(min instanceof Number) || !isProbability(((Number) min).doubleValue())) {
                        throw new IllegalArgumentException(
                                subject
                                        + " decision-model signal requires '"
                                        + MIN_PROBABILITY_KEY
                                        + "' in [0, 1].");
                    }
                    Object instructions = arguments.get(INSTRUCTIONS_KEY);
                    if (instructions != null
                            && (!(instructions instanceof String)
                                    || ((String) instructions).isBlank())) {
                        throw new IllegalArgumentException(
                                subject
                                        + " decision-model signal '"
                                        + INSTRUCTIONS_KEY
                                        + "' must be a non-empty String when provided.");
                    }
                    break;
                case TYPE_CUSTOM:
                    Object clazz = arguments.get(SIGNAL_CLASS_KEY);
                    if (!(clazz instanceof String) || ((String) clazz).isBlank()) {
                        throw new IllegalArgumentException(
                                subject
                                        + " custom signal requires a non-empty '"
                                        + SIGNAL_CLASS_KEY
                                        + "'.");
                    }
                    Object args = arguments.get(ARGS_KEY);
                    if (args != null && !(args instanceof Map)) {
                        throw new IllegalArgumentException(
                                subject + " custom signal '" + ARGS_KEY + "' must be a map.");
                    }
                    break;
                default:
                    throw new IllegalArgumentException(
                            subject + " has an escalation signal of unknown type '" + type + "'.");
            }
        }

        private static boolean isProbability(double value) {
            return !Double.isNaN(value) && value >= 0.0 && value <= 1.0;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Signal)) {
                return false;
            }
            Signal other = (Signal) o;
            return type.equals(other.type) && arguments.equals(other.arguments);
        }

        @Override
        public int hashCode() {
            return Objects.hash(type, arguments);
        }

        @Override
        public String toString() {
            return type + arguments;
        }
    }
}
