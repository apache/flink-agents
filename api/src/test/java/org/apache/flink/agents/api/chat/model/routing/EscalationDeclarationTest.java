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

import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Declaration-level rules of {@link Escalation} and its integration in the router builder. */
class EscalationDeclarationTest {

    /** A signal class for the declaration tests; never evaluated here. */
    public static final class AlwaysAccept implements QualitySignal {
        @Override
        public QualityVerdict evaluate(QualityContext context, RunnerContext runnerContext) {
            return QualityVerdict.accept();
        }
    }

    @Test
    void tiersRoundTripThroughTheDescriptorWithSignalsAndCap() throws Exception {
        Escalation declared =
                Escalation.tiers("small", "medium", "big")
                        .when(QualitySignals.decision("verifier", 0.7, "Does the answer fit?"))
                        .when(QualitySignals.custom(AlwaysAccept.class))
                        .maxEscalations(1);
        ResourceDescriptor descriptor =
                ModelRouter.of("small", "medium", "big")
                        .strategy(Strategies.rules(Map.of("big", "\\bsql\\b")))
                        .defaultModel("small")
                        .escalation(declared)
                        .build();
        ModelRouter router = new ModelRouter(descriptor, null);
        assertThat(router.getEscalation()).contains(declared);
        Escalation parsed = router.getEscalation().get();
        assertThat(parsed.getTiers()).containsExactly("small", "medium", "big");
        assertThat(parsed.getMaxEscalations()).isEqualTo(1);
        assertThat(parsed.getSignals()).hasSize(2);
        assertThat(parsed.getSignals().get(0).getType())
                .isEqualTo(Escalation.Signal.TYPE_DECISION_MODEL);
        assertThat(parsed.getSignals().get(0).decisionModel()).isEqualTo("verifier");
        assertThat(parsed.getSignals().get(0).minProbability()).isEqualTo(0.7);
        assertThat(parsed.getSignals().get(0).instructions()).isEqualTo("Does the answer fit?");
        assertThat(parsed.getSignals().get(1).signalClass())
                .isEqualTo(AlwaysAccept.class.getName());
        assertThat(parsed.nextTier("small")).contains("medium");
        assertThat(parsed.nextTier("big")).isEmpty();
        assertThat(parsed.tierIndex("medium")).isEqualTo(1);
        // The strategy is untouched by the policy.
        assertThat(router.getStrategy().getType()).isEqualTo(RoutingStrategyType.RULE_BASED);
    }

    @Test
    void tiersWithoutAStrategyStartOnTheFirstTierWhichBecomesTheDefault() throws Exception {
        ResourceDescriptor descriptor =
                ModelRouter.of("small", "big").escalation(Escalation.tiers("small", "big")).build();
        assertThat(descriptor.getInitialArguments())
                .doesNotContainKey(ModelRouter.STRATEGY_TYPE_KEY);
        ModelRouter router = new ModelRouter(descriptor, null);
        assertThat(router.getDefaultModel()).contains("small");
        assertThat(router.getStrategy().getType()).isEqualTo(RoutingStrategyType.RULE_BASED);
        assertThat(router.getEscalation().get().getMaxEscalations()).isEqualTo(1);
    }

    @Test
    void aDeclaredDefaultModelWinsOverTheFirstTier() throws Exception {
        ModelRouter router =
                new ModelRouter(
                        ModelRouter.of("small", "big")
                                .escalation(Escalation.tiers("small", "big"))
                                .defaultModel("big")
                                .build(),
                        null);
        assertThat(router.getDefaultModel()).contains("big");
    }

    @Test
    void routerStillRequiresAStrategyOrTiers() {
        assertThatThrownBy(() -> ModelRouter.of("small", "big").build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("strategy(...) or escalation(...)");
    }

    @Test
    void tiersMustBeCandidates() {
        assertThatThrownBy(
                        () ->
                                ModelRouter.of("small", "big")
                                        .escalation(Escalation.tiers("small", "huge"))
                                        .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tier 'huge' is not one of the candidates");
    }

    @Test
    void tiersNeedAtLeastTwoDistinctNonBlankNames() {
        assertThatThrownBy(() -> Escalation.tiers("small"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least two tiers");
        assertThatThrownBy(() -> Escalation.tiers("small", "small"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("more than once");
        assertThatThrownBy(() -> Escalation.tiers("small", " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-blank");
        assertThatThrownBy(() -> Escalation.tiers("small", "big").maxEscalations(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least 1");
    }

    @Test
    void theHopLimitIsBoundedByTheTierCount() {
        assertThat(Escalation.tiers("a", "b", "c").maxEscalations(5).getMaxEscalations())
                .isEqualTo(2);
        assertThat(Escalation.tiers("a", "b", "c").getMaxEscalations()).isEqualTo(2);
    }

    @Test
    void decisionSignalArgumentsAreChecked() {
        assertThatThrownBy(() -> QualitySignals.decision("verifier", 1.5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("min_probability");
        assertThatThrownBy(() -> QualitySignals.decision(" ", 0.5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("decision_model");
        assertThatThrownBy(() -> QualitySignals.decision("verifier", 0.5, " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("instructions");
        assertThatThrownBy(() -> QualitySignals.custom(" ", Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("signal_class");
    }

    /** A hand-built descriptor (no builder) is diagnosed field by field at construction. */
    @Test
    void handBuiltDescriptorsAreDiagnosed() {
        Map<String, Object> args = new HashMap<>();
        args.put(ModelRouter.CANDIDATES_KEY, List.of("small", "big"));
        args.put(ModelRouter.STRATEGY_TYPE_KEY, RoutingStrategyType.RULE_BASED.tag());
        args.put(ModelRouter.STRATEGY_ARGS_KEY, Map.of());
        args.put(Escalation.TIERS_KEY, "small,big");
        assertThatThrownBy(() -> new ModelRouter(descriptor(args), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expected a list of names");

        args.put(Escalation.TIERS_KEY, List.of("small", 7));
        assertThatThrownBy(() -> new ModelRouter(descriptor(args), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-String escalation tier");

        args.put(Escalation.TIERS_KEY, List.of("small", "big"));
        args.put(Escalation.SIGNALS_KEY, List.of(Map.of("type", "telepathy")));
        assertThatThrownBy(() -> new ModelRouter(descriptor(args), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown type 'telepathy'");

        args.put(Escalation.SIGNALS_KEY, List.of(Map.of("type", "decision_model")));
        assertThatThrownBy(() -> new ModelRouter(descriptor(args), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("decision_model");

        args.remove(Escalation.SIGNALS_KEY);
        args.put(Escalation.MAX_ESCALATIONS_KEY, 0);
        assertThatThrownBy(() -> new ModelRouter(descriptor(args), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive integer");

        args.remove(Escalation.TIERS_KEY);
        args.remove(Escalation.MAX_ESCALATIONS_KEY);
        args.put(Escalation.SIGNALS_KEY, List.of());
        assertThatThrownBy(() -> new ModelRouter(descriptor(args), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("without 'escalation_tiers'");
    }

    private static ResourceDescriptor descriptor(Map<String, Object> args) {
        return new ResourceDescriptor(ModelRouter.class.getName(), new HashMap<>(args));
    }
}
