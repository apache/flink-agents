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

package org.apache.flink.agents.plan.condition;

import dev.cel.common.CelAbstractSyntaxTree;
import dev.cel.common.CelValidationException;
import dev.cel.common.ast.CelExpr;
import dev.cel.common.navigation.CelNavigableAst;
import org.apache.flink.agents.api.EventType;
import org.apache.flink.agents.plan.condition.TriggerCondition.ExpressionCondition;

import java.util.Set;
import java.util.TreeSet;

/** Performs Plan-time syntax and static-policy validation for trigger condition expressions. */
public final class ConditionExpressionValidator {

    private static final Set<String> DISALLOWED_STANDARD_MACROS =
            Set.of("exists", "exists_one", "all", "filter", "map");
    private static final Set<String> ALLOWED_MACROS = Set.of("has");

    public enum ErrorCategory {
        SYNTAX,
        UNSUPPORTED_CONSTRUCT,
        UNKNOWN_EVENT_TYPE
    }

    /** A stable Plan-layer validation error with a machine-readable category. */
    public static final class ValidationException extends IllegalArgumentException {
        private final ErrorCategory category;

        private ValidationException(ErrorCategory category, String message, Throwable cause) {
            super(message, cause);
            this.category = category;
        }

        public ErrorCategory category() {
            return category;
        }
    }

    public static void validate(ExpressionCondition condition) {
        String source = condition.text();
        CelAbstractSyntaxTree parsed;
        try {
            parsed = ConditionExpressionDialect.parse(source);
        } catch (CelValidationException e) {
            throw new ValidationException(
                    ErrorCategory.SYNTAX,
                    "Invalid trigger condition expression \"" + source + "\": " + e.getMessage(),
                    e);
        }
        validateMacros(parsed, source);
        validateEventTypeReferences(parsed);
        validateStandalonePath(parsed, source);
    }

    private static void validateMacros(CelAbstractSyntaxTree parsed, String source) {
        String disallowedMacro =
                CelNavigableAst.fromAst(parsed)
                        .getRoot()
                        .allNodes()
                        .filter(node -> node.getKind() == CelExpr.ExprKind.Kind.CALL)
                        .map(node -> node.expr())
                        .filter(
                                expression -> {
                                    CelExpr.CelCall call = expression.call();
                                    if (!DISALLOWED_STANDARD_MACROS.contains(call.function())
                                            || call.target().isEmpty()
                                            || call.args().isEmpty()
                                            || call.args().get(0).getKind()
                                                    != CelExpr.ExprKind.Kind.IDENT) {
                                        return false;
                                    }
                                    int argumentCount = call.args().size();
                                    return "map".equals(call.function())
                                            ? argumentCount == 2 || argumentCount == 3
                                            : argumentCount == 2;
                                })
                        .map(expression -> expression.call().function())
                        .findFirst()
                        .orElse(null);
        if (disallowedMacro != null) {
            throw new ValidationException(
                    ErrorCategory.UNSUPPORTED_CONSTRUCT,
                    "Invalid trigger condition expression \""
                            + source
                            + "\": unsupported construct '"
                            + disallowedMacro
                            + "'. Supported: "
                            + new TreeSet<>(ALLOWED_MACROS)
                            + ".",
                    null);
        }
    }

    private static void validateEventTypeReferences(CelAbstractSyntaxTree parsed) {
        CelNavigableAst.fromAst(parsed)
                .getRoot()
                .allNodes()
                .filter(node -> node.getKind() == CelExpr.ExprKind.Kind.SELECT)
                .map(node -> node.expr().select())
                .filter(
                        selection ->
                                selection.operand().getKind() == CelExpr.ExprKind.Kind.IDENT
                                        && "EventType".equals(selection.operand().ident().name()))
                .forEach(
                        selection -> {
                            if (!EventType.allConstants().containsKey(selection.field())) {
                                throw new ValidationException(
                                        ErrorCategory.UNKNOWN_EVENT_TYPE,
                                        "Unknown EventType constant: EventType."
                                                + selection.field(),
                                        null);
                            }
                        });
    }

    private static void validateStandalonePath(CelAbstractSyntaxTree parsed, String source) {
        CelExpr root = CelNavigableAst.fromAst(parsed).getRoot().expr();
        boolean standalonePath =
                root.getKind() == CelExpr.ExprKind.Kind.IDENT
                        || (root.getKind() == CelExpr.ExprKind.Kind.SELECT
                                && !root.select().testOnly());
        if (standalonePath) {
            throw new ValidationException(
                    ErrorCategory.UNSUPPORTED_CONSTRUCT,
                    "Invalid trigger condition expression \""
                            + source
                            + "\": a standalone path is not a Boolean condition; use an explicit"
                            + " comparison.",
                    null);
        }
    }

    private ConditionExpressionValidator() {}
}
