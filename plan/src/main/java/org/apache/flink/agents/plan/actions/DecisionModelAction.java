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
package org.apache.flink.agents.plan.actions;

import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.api.decision.BaseDecisionModelSetup;
import org.apache.flink.agents.api.event.DecisionRequestEvent;
import org.apache.flink.agents.api.event.DecisionResponseEvent;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.plan.JavaFunction;

import java.util.List;

/** Executes decision requests durably and emits one correlated success or failure response. */
public final class DecisionModelAction {

    private DecisionModelAction() {}

    public static Action getDecisionModelAction() throws Exception {
        return new Action(
                "decision_model_action",
                new JavaFunction(
                        DecisionModelAction.class,
                        "processDecisionRequest",
                        new Class[] {Event.class, RunnerContext.class}),
                List.of(DecisionRequestEvent.EVENT_TYPE));
    }

    /**
     * Handles a decision request with the configured retry budget. Resource lookup failures,
     * cancellation and durable-state failures propagate instead of becoming failed decision
     * responses. The call itself runs through {@link DecisionModelInvoker} under the flat durable
     * id {@code "decide"}.
     */
    public static void processDecisionRequest(Event event, RunnerContext ctx) throws Exception {
        DecisionRequestEvent request = (DecisionRequestEvent) event;
        BaseDecisionModelSetup model =
                (BaseDecisionModelSetup)
                        ctx.getResource(request.getModel(), ResourceType.DECISION_MODEL);
        DecisionModelInvoker.DecisionAttemptResult result =
                DecisionModelInvoker.decideWithRetries(
                        ctx,
                        request.getModel(),
                        model,
                        request.getRequest(),
                        request.getRequestParams(),
                        "decide",
                        String.valueOf(request.getId()),
                        true);
        DecisionResponseEvent terminal =
                result.isSuccess()
                        ? DecisionResponseEvent.success(
                                request.getId(),
                                result.response,
                                result.retryCount,
                                result.totalWaitSec)
                        : DecisionResponseEvent.failed(
                                request.getId(),
                                result.error,
                                result.retryCount,
                                result.totalWaitSec);
        ctx.sendEvent(terminal);
    }
}
