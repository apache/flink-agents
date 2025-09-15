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
package org.apache.flink.agents.api.configuration;

/** The set of configuration options for agents parameters. */
public class AgentConfigOptions {

    /** The config parameter specifies the directory for the FileEvent file. */
    public static final ConfigOption<String> BASE_LOG_DIR =
            new ConfigOption<>("baseLogDir", String.class, null);

    /** The config parameter specifies the backend for action state store. */
    public static final ConfigOption<String> ACTION_STATE_STORE_BACKEND =
            new ConfigOption<>("actionStateStoreBackend", String.class, "inmemory");
}
