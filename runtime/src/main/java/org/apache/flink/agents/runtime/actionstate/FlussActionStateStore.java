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

import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.plan.AgentConfiguration;
import org.apache.flink.agents.plan.actions.Action;
import org.apache.flink.annotation.VisibleForTesting;
import org.apache.fluss.client.Connection;
import org.apache.fluss.client.ConnectionFactory;
import org.apache.fluss.client.admin.Admin;
import org.apache.fluss.client.admin.OffsetSpec;
import org.apache.fluss.client.table.Table;
import org.apache.fluss.client.table.scanner.ScanRecord;
import org.apache.fluss.client.table.scanner.log.LogScanner;
import org.apache.fluss.client.table.scanner.log.ScanRecords;
import org.apache.fluss.client.table.writer.AppendWriter;
import org.apache.fluss.config.ConfigOptions;
import org.apache.fluss.config.Configuration;
import org.apache.fluss.metadata.DatabaseDescriptor;
import org.apache.fluss.metadata.Schema;
import org.apache.fluss.metadata.TableBucket;
import org.apache.fluss.metadata.TableDescriptor;
import org.apache.fluss.metadata.TablePath;
import org.apache.fluss.row.BinaryString;
import org.apache.fluss.row.GenericRow;
import org.apache.fluss.row.InternalRow;
import org.apache.fluss.types.DataTypes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.apache.flink.agents.api.configuration.AgentConfigOptions.FLUSS_ACTION_STATE_DATABASE;
import static org.apache.flink.agents.api.configuration.AgentConfigOptions.FLUSS_ACTION_STATE_TABLE;
import static org.apache.flink.agents.api.configuration.AgentConfigOptions.FLUSS_ACTION_STATE_TABLE_BUCKETS;
import static org.apache.flink.agents.api.configuration.AgentConfigOptions.FLUSS_BOOTSTRAP_SERVERS;
import static org.apache.flink.agents.api.configuration.AgentConfigOptions.FLUSS_SASL_JAAS_CONFIG;
import static org.apache.flink.agents.api.configuration.AgentConfigOptions.FLUSS_SASL_MECHANISM;
import static org.apache.flink.agents.api.configuration.AgentConfigOptions.FLUSS_SASL_PASSWORD;
import static org.apache.flink.agents.api.configuration.AgentConfigOptions.FLUSS_SASL_USERNAME;
import static org.apache.flink.agents.api.configuration.AgentConfigOptions.FLUSS_SECURITY_PROTOCOL;
import static org.apache.flink.agents.runtime.actionstate.ActionStateUtil.generateKey;
import static org.apache.fluss.config.ConfigOptions.BOOTSTRAP_SERVERS;
import static org.apache.fluss.config.ConfigOptions.CLIENT_SASL_JAAS_CONFIG;
import static org.apache.fluss.config.ConfigOptions.CLIENT_SASL_JAAS_PASSWORD;
import static org.apache.fluss.config.ConfigOptions.CLIENT_SASL_JAAS_USERNAME;
import static org.apache.fluss.config.ConfigOptions.CLIENT_SASL_MECHANISM;
import static org.apache.fluss.config.ConfigOptions.CLIENT_SECURITY_PROTOCOL;

/**
 * An implementation of {@link ActionStateStore} that uses an Apache Fluss log table as the backend.
 * All state is maintained in an in-memory map for fast lookups, with the Fluss log table providing
 * durability and recovery support.
 */
public class FlussActionStateStore implements ActionStateStore {

    private static final Logger LOG = LoggerFactory.getLogger(FlussActionStateStore.class);

    private static final Duration POLL_TIMEOUT = Duration.ofSeconds(1);

    private static final String SECURITY_PROTOCOL_PLAINTEXT = "PLAINTEXT";

    // Column names in the Fluss table schema
    private static final String COL_NAME_STATE_KEY = "state_key";
    private static final String COL_NAME_STATE_PAYLOAD = "state_payload";
    private static final String COL_NAME_AGENT_KEY = "agent_key";

    // Column indices in the Fluss table schema
    private static final int COL_STATE_KEY = 0;
    private static final int COL_STATE_PAYLOAD = 1;

    private final AgentConfiguration agentConfiguration;
    private final String databaseName;
    private final String tableName;
    private final TablePath tablePath;

    private final Connection connection;
    private final Table table;
    private final AppendWriter writer;

    /** In-memory cache for O(1) state lookups; rebuilt from Fluss log on recovery. */
    private final Map<String, ActionState> actionStates;

    @VisibleForTesting
    FlussActionStateStore(
            Map<String, ActionState> actionStates,
            Connection connection,
            Table table,
            AppendWriter writer) {
        this.agentConfiguration = null;
        this.databaseName = null;
        this.tableName = null;
        this.tablePath = null;
        this.actionStates = actionStates;
        this.connection = connection;
        this.table = table;
        this.writer = writer;
    }

    public FlussActionStateStore(AgentConfiguration agentConfiguration) {
        this.agentConfiguration = agentConfiguration;
        this.databaseName = agentConfiguration.get(FLUSS_ACTION_STATE_DATABASE);
        this.tableName = agentConfiguration.get(FLUSS_ACTION_STATE_TABLE);
        this.tablePath = TablePath.of(databaseName, tableName);
        this.actionStates = new HashMap<>();

        Configuration flussConf = new Configuration();
        flussConf.setString(
                BOOTSTRAP_SERVERS.key(), agentConfiguration.get(FLUSS_BOOTSTRAP_SERVERS));
        // Minimize latency for synchronous put(): setting batch linger time to zero ensures
        // that each append is sent immediately without waiting for additional records to batch.
        flussConf.set(ConfigOptions.CLIENT_WRITER_BATCH_TIMEOUT, Duration.ZERO);

        // Only set security/SASL parameters when the protocol requires authentication.
        // When PLAINTEXT (the default), SASL parameters are semantically invalid and may
        // cause the Fluss client to attempt an unwanted SASL handshake.
        String securityProtocol = agentConfiguration.get(FLUSS_SECURITY_PROTOCOL);
        flussConf.setString(CLIENT_SECURITY_PROTOCOL, securityProtocol);
        if (!SECURITY_PROTOCOL_PLAINTEXT.equalsIgnoreCase(securityProtocol)) {
            flussConf.setString(
                    CLIENT_SASL_MECHANISM, agentConfiguration.get(FLUSS_SASL_MECHANISM));

            String jaasConfig = agentConfiguration.get(FLUSS_SASL_JAAS_CONFIG);
            if (jaasConfig != null) {
                flussConf.setString(CLIENT_SASL_JAAS_CONFIG, jaasConfig);
            }
            String username = agentConfiguration.get(FLUSS_SASL_USERNAME);
            if (username != null) {
                flussConf.setString(CLIENT_SASL_JAAS_USERNAME, username);
            }
            String password = agentConfiguration.get(FLUSS_SASL_PASSWORD);
            if (password != null) {
                flussConf.setString(CLIENT_SASL_JAAS_PASSWORD, password);
            }
        }

        this.connection = ConnectionFactory.createConnection(flussConf);
        maybeCreateDatabaseAndTable();
        this.table = connection.getTable(tablePath);
        this.writer = table.newAppend().createWriter();

        LOG.info(
                "Initialized FlussActionStateStore (log table) with table: {}.{}",
                databaseName,
                tableName);
    }

    @Override
    public void put(Object key, long seqNum, Action action, Event event, ActionState state)
            throws Exception {
        String stateKey = generateKey(key, seqNum, action, event);
        byte[] payload = ActionStateSerde.serialize(state);

        GenericRow row =
                GenericRow.of(
                        BinaryString.fromString(stateKey),
                        payload,
                        BinaryString.fromString(key.toString()));

        // Append to Fluss log for durability, then update in-memory cache.
        // Synchronous write ensures the record is durable before returning.
        writer.append(row).get();
        actionStates.put(stateKey, state);

        LOG.debug("Stored action state: key={}, isCompleted={}", stateKey, state.isCompleted());
    }

    @Override
    public ActionState get(Object key, long seqNum, Action action, Event event) throws Exception {
        String stateKey = generateKey(key, seqNum, action, event);

        boolean hasDivergence = checkDivergence(key.toString(), seqNum);

        if (!actionStates.containsKey(stateKey) || hasDivergence) {
            actionStates
                    .entrySet()
                    .removeIf(
                            entry -> {
                                try {
                                    List<String> parts = ActionStateUtil.parseKey(entry.getKey());
                                    if (parts.size() >= 2) {
                                        long stateSeqNum = Long.parseLong(parts.get(1));
                                        return stateSeqNum > seqNum;
                                    }
                                } catch (NumberFormatException e) {
                                    LOG.warn(
                                            "Failed to parse sequence number from state key: {}",
                                            stateKey);
                                }
                                return false;
                            });
        }

        ActionState state = actionStates.get(stateKey);
        LOG.debug("Lookup action state: key={}, found={}", stateKey, state != null);
        return state;
    }

    private boolean checkDivergence(String key, long seqNum) {
        return actionStates.keySet().stream()
                        .filter(k -> k.startsWith(key + "_" + seqNum + "_"))
                        .count()
                > 1;
    }

    /**
     * Rebuilds in-memory state by scanning the Fluss log table. If recovery markers are provided,
     * computes the minimum offset per bucket across all markers and subscribes from those offsets.
     * Otherwise, skips rebuild since there is no checkpointed position to recover from. Reads from
     * the start offset up to the latest offset captured at rebuild start. For the same state key
     * appearing multiple times in the log, the latest record wins (last-write-wins).
     */
    @Override
    public void rebuildState(List<Object> recoveryMarkers) {
        LOG.info(
                "Rebuilding action state from Fluss log table with {} recovery markers",
                recoveryMarkers.size());

        if (recoveryMarkers.isEmpty()) {
            LOG.info("No recovery markers, skipping state rebuild");
            return;
        }

        actionStates.clear();

        Map<Integer, Long> bucketStartOffsets = mergeRecoveryMarkerOffsets(recoveryMarkers);
        if (bucketStartOffsets.isEmpty()) {
            LOG.info("No valid bucket offsets in recovery markers, skipping state rebuild");
            return;
        }

        Map<Integer, Long> bucketEndOffsets = getBucketEndOffsets();
        Map<Integer, Long> bucketEarliestOffsets = getBucketEarliestOffsets();
        LOG.debug(
                "Rebuild window: startOffsets={}, earliestOffsets={}, endOffsets={}",
                bucketStartOffsets,
                bucketEarliestOffsets,
                bucketEndOffsets);

        try (LogScanner scanner = table.newScan().createLogScanner()) {
            Map<Integer, Long> remainingBuckets =
                    subscribeEffectiveOffsets(
                            scanner, bucketStartOffsets, bucketEndOffsets, bucketEarliestOffsets);
            LOG.debug("Subscribed buckets for rebuild: {}", remainingBuckets);

            pollAndReplay(scanner, remainingBuckets);
        } catch (Exception e) {
            throw new RuntimeException("Failed to rebuild state from Fluss log table", e);
        }

        LOG.info("Completed rebuilding state, recovered {} states", actionStates.size());
    }

    /**
     * Merges recovery markers into a per-bucket start offset map. For each bucket, the minimum
     * offset across all markers is used to cover the widest recovery window.
     */
    private Map<Integer, Long> mergeRecoveryMarkerOffsets(List<Object> recoveryMarkers) {
        Map<Integer, Long> bucketStartOffsets = new HashMap<>();
        for (Object marker : recoveryMarkers) {
            if (marker instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<Integer, Long> markerMap = (Map<Integer, Long>) marker;
                for (Map.Entry<Integer, Long> entry : markerMap.entrySet()) {
                    bucketStartOffsets.merge(entry.getKey(), entry.getValue(), Math::min);
                }
            } else if (marker != null) {
                LOG.warn(
                        "Ignoring unrecognized recovery marker type: {}",
                        marker.getClass().getName());
            }
        }
        return bucketStartOffsets;
    }

    /**
     * Validates effective offsets for each bucket and subscribes the scanner. Buckets with no new
     * data are skipped; buckets with data loss (retention cleaned the recovery window) cause an
     * immediate failure.
     *
     * @return a map of bucket-id to end-offset for buckets that need to be scanned
     */
    private Map<Integer, Long> subscribeEffectiveOffsets(
            LogScanner scanner,
            Map<Integer, Long> bucketStartOffsets,
            Map<Integer, Long> bucketEndOffsets,
            Map<Integer, Long> bucketEarliestOffsets) {
        Map<Integer, Long> remainingBuckets = new HashMap<>();
        for (Map.Entry<Integer, Long> entry : bucketStartOffsets.entrySet()) {
            int bucket = entry.getKey();
            long startOffset = entry.getValue();
            long endOffset = bucketEndOffsets.get(bucket);
            long earliestOffset = bucketEarliestOffsets.get(bucket);

            // No new data since checkpoint (includes empty buckets that never had writes:
            // endOffset=0, startOffset=0)
            if (endOffset == startOffset) {
                LOG.info(
                        "Skipping bucket {} for rebuild: no new data "
                                + "(endOffset={} = startOffset={})",
                        bucket,
                        endOffset,
                        startOffset);
                continue;
            }

            // Data loss: retention cleaned the recovery window, or log was truncated/reset
            if (earliestOffset > startOffset || endOffset < startOffset) {
                throw new IllegalStateException(
                        String.format(
                                "Data loss detected for bucket %d: required data is no longer "
                                        + "available in the log (startOffset=%d, endOffset=%d, "
                                        + "earliestOffset=%d). Increase log retention or reduce "
                                        + "checkpoint interval.",
                                bucket, startOffset, endOffset, earliestOffset));
            }

            scanner.subscribe(bucket, startOffset);
            remainingBuckets.put(bucket, endOffset);
        }
        return remainingBuckets;
    }

    /**
     * Polls the scanner and replays deserialized action states until all subscribed buckets have
     * been fully consumed up to their end offsets.
     */
    private void pollAndReplay(LogScanner scanner, Map<Integer, Long> remainingBuckets) {
        while (!remainingBuckets.isEmpty()) {
            ScanRecords records = scanner.poll(POLL_TIMEOUT);
            for (TableBucket bucket : records.buckets()) {
                Long endOffset = remainingBuckets.get(bucket.getBucket());
                long lastSeenOffset = replayRecords(records.records(bucket), endOffset);
                if (lastSeenOffset + 1 >= endOffset) {
                    remainingBuckets.remove(bucket.getBucket());
                    scanner.unsubscribe(bucket.getBucket());
                }
            }
        }
    }

    /**
     * Replays records from a single bucket, deserializing and applying each action state. Returns
     * the highest log offset seen (including records past endOffset), used to detect bucket
     * completion.
     */
    private long replayRecords(Iterable<ScanRecord> records, long endOffset) {
        long lastSeenOffset = -1;
        for (ScanRecord record : records) {
            lastSeenOffset = record.logOffset();
            if (record.logOffset() >= endOffset) {
                break;
            }
            InternalRow row = record.getRow();
            String stateKey = row.getString(COL_STATE_KEY).toString();
            byte[] payload = row.getBytes(COL_STATE_PAYLOAD);
            ActionState state = ActionStateSerde.deserialize(payload);
            actionStates.put(stateKey, state);
        }
        return lastSeenOffset;
    }

    private Map<Integer, Long> getBucketEndOffsets() {
        return getBucketOffsets(new OffsetSpec.LatestSpec());
    }

    private Map<Integer, Long> getBucketEarliestOffsets() {
        return getBucketOffsets(new OffsetSpec.EarliestSpec());
    }

    private Map<Integer, Long> getBucketOffsets(OffsetSpec offsetSpec) {
        int numBuckets = table.getTableInfo().getNumBuckets();
        try (Admin admin = connection.getAdmin()) {
            List<Integer> buckets = new ArrayList<>();
            for (int b = 0; b < numBuckets; b++) {
                buckets.add(b);
            }
            return admin.listOffsets(tablePath, buckets, offsetSpec).all().get();
        } catch (Exception e) {
            throw new RuntimeException("Failed to get offsets for Fluss table: " + tablePath, e);
        }
    }

    /**
     * Returns the end offsets of each bucket as a recovery marker. Similar to Kafka's
     * implementation, this captures the current log position so that {@link #rebuildState} can
     * resume from these offsets instead of scanning from the beginning.
     */
    @Override
    public Object getRecoveryMarker() {
        return getBucketEndOffsets();
    }

    /**
     * Evicts pruned states from the in-memory cache. The Fluss log is append-only; physical cleanup
     * relies on Fluss log retention configuration.
     */
    @Override
    public void pruneState(Object key, long seqNum) {
        LOG.debug("Pruning in-memory state for key: {} up to seqNum: {}", key, seqNum);

        actionStates
                .entrySet()
                .removeIf(
                        entry -> {
                            String stateKey = entry.getKey();
                            if (stateKey.startsWith(key.toString() + "_")) {
                                try {
                                    List<String> parts = ActionStateUtil.parseKey(stateKey);
                                    if (parts.size() >= 2) {
                                        long stateSeqNum = Long.parseLong(parts.get(1));
                                        return stateSeqNum <= seqNum;
                                    }
                                } catch (Exception e) {
                                    LOG.warn("Failed to parse state key: {}", stateKey, e);
                                }
                            }
                            return false;
                        });
    }

    @Override
    public void close() throws Exception {
        try {
            if (table != null) {
                table.close();
            }
        } finally {
            if (connection != null) {
                connection.close();
            }
        }
    }

    private void maybeCreateDatabaseAndTable() {
        try (Admin admin = connection.getAdmin()) {
            if (!admin.databaseExists(databaseName).get()) {
                admin.createDatabase(databaseName, DatabaseDescriptor.EMPTY, true).get();
                LOG.info("Created Fluss database: {}", databaseName);
            }

            if (!admin.tableExists(tablePath).get()) {
                // No primaryKey() call — this creates an append-only log table in Fluss.
                Schema schema =
                        Schema.newBuilder()
                                .column(COL_NAME_STATE_KEY, DataTypes.STRING())
                                .column(COL_NAME_STATE_PAYLOAD, DataTypes.BYTES())
                                .column(COL_NAME_AGENT_KEY, DataTypes.STRING())
                                .build();

                int buckets = agentConfiguration.get(FLUSS_ACTION_STATE_TABLE_BUCKETS);
                TableDescriptor descriptor =
                        TableDescriptor.builder()
                                .schema(schema)
                                .distributedBy(buckets, COL_NAME_AGENT_KEY)
                                .comment("Flink Agents action state log")
                                .build();

                admin.createTable(tablePath, descriptor, true).get();
                LOG.info("Created Fluss log table: {}", tablePath);
            } else {
                LOG.info("Fluss table {} already exists", tablePath);
            }
        } catch (Exception e) {
            LOG.error(
                    "Failed to create or verify Fluss database/table: {}.{}",
                    databaseName,
                    tableName,
                    e);
            throw new RuntimeException("Failed to create or verify Fluss database/table", e);
        }
    }
}
