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
package org.apache.flink.agents.runtime.actionstate;

import org.apache.flink.agents.api.InputEvent;
import org.apache.flink.agents.plan.AgentConfiguration;
import org.apache.flink.agents.plan.actions.Action;
import org.apache.fluss.config.ConfigOptions;
import org.apache.fluss.config.Configuration;
import org.apache.fluss.server.testutils.FlussClusterExtension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static org.apache.flink.agents.api.configuration.AgentConfigOptions.FLUSS_ACTION_STATE_DATABASE;
import static org.apache.flink.agents.api.configuration.AgentConfigOptions.FLUSS_ACTION_STATE_TABLE;
import static org.apache.flink.agents.api.configuration.AgentConfigOptions.FLUSS_ACTION_STATE_TABLE_BUCKETS;
import static org.apache.flink.agents.api.configuration.AgentConfigOptions.FLUSS_BOOTSTRAP_SERVERS;
import static org.apache.flink.agents.api.configuration.AgentConfigOptions.FLUSS_SASL_MECHANISM;
import static org.apache.flink.agents.api.configuration.AgentConfigOptions.FLUSS_SASL_PASSWORD;
import static org.apache.flink.agents.api.configuration.AgentConfigOptions.FLUSS_SASL_USERNAME;
import static org.apache.flink.agents.api.configuration.AgentConfigOptions.FLUSS_SECURITY_PROTOCOL;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link FlussActionStateStore} with SASL/PLAIN authentication against an
 * embedded Fluss cluster.
 */
public class FlussActionStateStoreSaslIT {

    private static final String TEST_DATABASE = "test_flink_agents_sasl";
    private static final String TEST_TABLE = "action_state_sasl_it";
    private static final String TEST_KEY = "test-key";
    private static final String SASL_USERNAME = "testuser";
    private static final String SASL_PASSWORD = "testpass";

    @RegisterExtension
    static final FlussClusterExtension FLUSS_CLUSTER =
            FlussClusterExtension.builder()
                    .setNumOfTabletServers(1)
                    .setCoordinatorServerListeners("FLUSS://localhost:0, CLIENT://localhost:0")
                    .setTabletServerListeners("FLUSS://localhost:0, CLIENT://localhost:0")
                    .setClusterConf(createSaslClusterConfig())
                    .build();

    private FlussActionStateStore store;

    @BeforeEach
    void setUp() throws Exception {
        AgentConfiguration config = createSaslAgentConfiguration();
        store = new FlussActionStateStore(config);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (store != null) {
            store.close();
        }
    }

    @Test
    void testPutAndGetWithSaslAuth() throws Exception {
        Action testAction = new NoOpAction("sasl-action");
        InputEvent testEvent = new InputEvent("sasl-data");
        ActionState state = new ActionState(testEvent);

        store.put(TEST_KEY, 1L, testAction, testEvent, state);
        ActionState retrieved = store.get(TEST_KEY, 1L, testAction, testEvent);

        assertThat(retrieved).isNotNull();
        assertThat(retrieved.getTaskEvent()).isEqualTo(testEvent);
    }

    @Test
    void testRecoveryWithSaslAuth() throws Exception {
        Action testAction = new NoOpAction("sasl-recovery-action");
        InputEvent testEvent = new InputEvent("sasl-recovery-data");

        // Write data and capture recovery marker
        store.put(TEST_KEY, 1L, testAction, testEvent, new ActionState(testEvent));
        Object marker = store.getRecoveryMarker();

        // Write more data after marker
        store.put(TEST_KEY, 2L, testAction, testEvent, new ActionState(testEvent));
        store.close();

        // Recover into a new store instance with SASL
        FlussActionStateStore recoveredStore =
                new FlussActionStateStore(createSaslAgentConfiguration());
        try {
            recoveredStore.rebuildState(java.util.List.of(marker));

            // Data written after marker should be recovered
            assertThat(recoveredStore.get(TEST_KEY, 2L, testAction, testEvent)).isNotNull();
            // Data written before marker should NOT be in the rebuilt cache
            assertThat(recoveredStore.get(TEST_KEY, 1L, testAction, testEvent)).isNull();
        } finally {
            recoveredStore.close();
            store = null;
        }
    }

    @Test
    void testMultipleWritesWithSaslAuth() throws Exception {
        Action testAction = new NoOpAction("sasl-multi-action");
        InputEvent testEvent = new InputEvent("sasl-multi-data");

        for (int i = 0; i < 5; i++) {
            store.put("key-" + i, (long) i, testAction, testEvent, new ActionState(testEvent));
        }

        for (int i = 0; i < 5; i++) {
            assertThat(store.get("key-" + i, (long) i, testAction, testEvent)).isNotNull();
        }
    }

    // ==================== Helpers ====================

    private static Configuration createSaslClusterConfig() {
        Configuration conf = new Configuration();
        conf.setString(ConfigOptions.SERVER_SECURITY_PROTOCOL_MAP.key(), "CLIENT:sasl");
        conf.setString("security.sasl.enabled.mechanisms", "plain");
        conf.setString(
                "security.sasl.plain.jaas.config",
                "org.apache.fluss.security.auth.sasl.plain.PlainLoginModule required "
                        + "    user_"
                        + SASL_USERNAME
                        + "=\""
                        + SASL_PASSWORD
                        + "\";");
        return conf;
    }

    private AgentConfiguration createSaslAgentConfiguration() {
        AgentConfiguration config = new AgentConfiguration();
        String bootstrapServers =
                String.join(
                        ",",
                        FLUSS_CLUSTER
                                .getClientConfig("CLIENT")
                                .get(ConfigOptions.BOOTSTRAP_SERVERS));
        config.set(FLUSS_BOOTSTRAP_SERVERS, bootstrapServers);
        config.set(FLUSS_ACTION_STATE_DATABASE, TEST_DATABASE);
        config.set(FLUSS_ACTION_STATE_TABLE, TEST_TABLE);
        config.set(FLUSS_ACTION_STATE_TABLE_BUCKETS, 1);
        config.set(FLUSS_SECURITY_PROTOCOL, "SASL");
        config.set(FLUSS_SASL_MECHANISM, "PLAIN");
        config.set(FLUSS_SASL_USERNAME, SASL_USERNAME);
        config.set(FLUSS_SASL_PASSWORD, SASL_PASSWORD);
        return config;
    }
}
