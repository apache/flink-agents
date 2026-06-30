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

package org.apache.flink.agents.runtime.condition;

import dev.cel.common.CelAbstractSyntaxTree;
import dev.cel.common.CelValidationException;
import dev.cel.common.Operator;
import dev.cel.common.ast.CelConstant;
import dev.cel.common.ast.CelExpr;
import dev.cel.common.navigation.CelNavigableAst;
import dev.cel.common.navigation.CelNavigableExpr;
import dev.cel.common.types.CelType;
import dev.cel.common.types.MapType;
import dev.cel.common.types.SimpleType;
import dev.cel.compiler.CelCompilerBuilder;
import dev.cel.compiler.CelCompilerFactory;
import dev.cel.runtime.CelEvaluationException;
import dev.cel.runtime.CelRuntime;
import dev.cel.runtime.CelRuntimeFactory;
import org.apache.flink.agents.plan.condition.ActionSelector.ConditionExpression;
import org.apache.flink.agents.plan.condition.ConditionSyntaxPolicy;

import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Compiles Plan-classified expressions into executable programs and referenced attribute keys. */
final class ConditionExpressionCompiler {

    /** Condition variables and the user-attribute namespace with their types at compile time. */
    private static final Map<String, CelType> FRAMEWORK_VARIABLE_TYPES =
            Map.of(
                    "type",
                    SimpleType.STRING,
                    "EventType",
                    MapType.create(SimpleType.STRING, SimpleType.STRING),
                    "attributes",
                    MapType.create(SimpleType.STRING, SimpleType.DYN));

    private static final CelRuntime CEL_RUNTIME =
            CelRuntimeFactory.standardCelRuntimeBuilder()
                    .setOptions(ConditionSyntaxPolicy.CEL_OPTIONS)
                    .build();

    private ConditionExpressionCompiler() {}

    /** Re-parses and compiles a Plan-classified expression during Runtime initialization. */
    static CompiledCondition compile(ConditionExpression condition) {
        Objects.requireNonNull(condition, "condition");
        String source = condition.text();

        CelAbstractSyntaxTree parsed;
        try {
            parsed = ConditionSyntaxPolicy.PARSER.parse(source).getAst();
        } catch (CelValidationException e) {
            throw new IllegalArgumentException(
                    "Plan-validated trigger condition could not be reparsed at Runtime: \""
                            + source
                            + "\" — "
                            + e.getMessage(),
                    e);
        }

        CelAbstractSyntaxTree checked = typeCheck(source, parsed);
        CelType resultType = checked.getResultType();
        if (!resultType.kind().isDyn() && !SimpleType.BOOL.isAssignableFrom(resultType)) {
            throw new IllegalArgumentException(
                    "Trigger condition must have a Boolean result at Runtime: \""
                            + source
                            + "\" has static type "
                            + resultType.name());
        }
        try {
            CelRuntime.Program program = CEL_RUNTIME.createProgram(checked);
            Set<String> referencedTopLevelAttributeKeys = new HashSet<>();
            CelNavigableAst.fromAst(parsed)
                    .getRoot()
                    .allNodes()
                    .filter(node -> node.getKind() == CelExpr.ExprKind.Kind.IDENT)
                    .forEach(node -> classifyIdent(node, referencedTopLevelAttributeKeys, source));
            return new CompiledCondition(source, program, referencedTopLevelAttributeKeys);
        } catch (CelEvaluationException e) {
            throw new IllegalArgumentException(
                    "Failed to create Runtime program for trigger condition: \""
                            + source
                            + "\" — "
                            + e.getMessage(),
                    e);
        }
    }

    private static CelAbstractSyntaxTree typeCheck(String source, CelAbstractSyntaxTree parsed) {
        CelCompilerBuilder builder =
                CelCompilerFactory.standardCelCompilerBuilder()
                        .setOptions(ConditionSyntaxPolicy.CEL_OPTIONS);
        FRAMEWORK_VARIABLE_TYPES.forEach(builder::addVar);
        CelNavigableAst.fromAst(parsed)
                .getRoot()
                .allNodes()
                .filter(node -> node.getKind() == CelExpr.ExprKind.Kind.IDENT)
                .map(node -> node.expr().ident().name())
                .filter(ident -> !FRAMEWORK_VARIABLE_TYPES.containsKey(ident))
                .distinct()
                .forEach(ident -> builder.addVar(ident, SimpleType.DYN));

        try {
            return builder.build().check(parsed).getAst();
        } catch (CelValidationException e) {
            throw new IllegalArgumentException(
                    "Trigger condition failed Runtime type-check: \""
                            + source
                            + "\" — "
                            + e.getMessage(),
                    e);
        }
    }

    private static void classifyIdent(CelNavigableExpr node, Set<String> keys, String source) {
        String name = node.expr().ident().name();
        // type/EventType are framework-owned (put()), never attribute keys. Expression operators,
        // type conversions and macros never appear as a bare IDENT, so no further skip list is
        // needed.
        if (name.equals("type") || name.equals("EventType")) {
            return;
        }
        if (!name.equals("attributes")) {
            // Bare key (includes id, which a user attribute may override). A dynamic index key
            // such as attributes[region_id] also references region_id's value — keep it.
            keys.add(name);
            return;
        }
        Optional<CelNavigableExpr> parent = node.parent();
        if (parent.isPresent() && parent.get().getKind() == CelExpr.ExprKind.Kind.SELECT) {
            keys.add(parent.get().expr().select().field());
            return;
        }

        CelExpr keyExpr = null;
        if (parent.isPresent() && parent.get().getKind() == CelExpr.ExprKind.Kind.CALL) {
            CelExpr.CelCall call = parent.get().expr().call();
            if (call.args().size() == 2) {
                if (Operator.INDEX.getFunction().equals(call.function())
                        && call.args().get(0).id() == node.id()) {
                    keyExpr = call.args().get(1);
                } else if (Operator.IN.getFunction().equals(call.function())
                        && call.args().get(1).id() == node.id()) {
                    keyExpr = call.args().get(0);
                }
            }
        }

        if (keyExpr != null
                && keyExpr.getKind() == CelExpr.ExprKind.Kind.CONSTANT
                && keyExpr.constant().getKind() == CelConstant.Kind.STRING_VALUE) {
            keys.add(keyExpr.constant().stringValue());
            return;
        }

        // Whole-root-map operations and dynamic root-key access cannot be evaluated against the
        // trimmed attributes activation. Static root keys and nested maps below them remain
        // supported.
        throw new IllegalArgumentException(
                "Invalid trigger condition expression: \""
                        + source
                        + "\" — the whole 'attributes' map cannot be used (e.g. dynamic index"
                        + " attributes[expr], size(attributes), or attributes == ...); reference"
                        + " attributes by a literal key such as attributes['key'], 'key' in"
                        + " attributes, or has(attributes.key).");
    }

    /** Immutable result shared by condition evaluation and activation construction. */
    static final class CompiledCondition {
        private final String source;
        private final CelRuntime.Program program;
        private final Set<String> referencedTopLevelAttributeKeys;

        private CompiledCondition(
                String source,
                CelRuntime.Program program,
                Set<String> referencedTopLevelAttributeKeys) {
            this.source = source;
            this.program = program;
            this.referencedTopLevelAttributeKeys = Set.copyOf(referencedTopLevelAttributeKeys);
        }

        String source() {
            return source;
        }

        CelRuntime.Program program() {
            return program;
        }

        Set<String> referencedTopLevelAttributeKeys() {
            return referencedTopLevelAttributeKeys;
        }
    }
}
