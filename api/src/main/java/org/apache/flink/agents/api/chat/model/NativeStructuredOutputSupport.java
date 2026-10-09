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

package org.apache.flink.agents.api.chat.model;

/**
 * A connection's answer about applying the provider's native structured-output API to one request.
 *
 * @see BaseChatModelConnection#supportsNativeStructuredOutput(Object, java.util.List,
 *     java.util.Map)
 * @see StructuredOutputStrategy#resolvesToNative(NativeStructuredOutputSupport)
 */
public enum NativeStructuredOutputSupport {
    /**
     * No request this connection builds from these inputs can carry the schema. Binding: no policy
     * can overrule it.
     */
    INFEASIBLE,

    /**
     * The request can carry the schema, but the effective model is not known to honor it. Advisory:
     * an explicit policy may still choose the native API, and a request built with the schema
     * carries it exactly as under {@link #NATIVE_RECOMMENDED}.
     */
    FEASIBLE,

    /** The request can carry the schema, and the effective model is known to honor it. */
    NATIVE_RECOMMENDED
}
