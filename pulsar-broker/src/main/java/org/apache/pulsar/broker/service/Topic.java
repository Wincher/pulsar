/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.pulsar.broker.service;

import io.netty.buffer.ByteBuf;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.apache.bookkeeper.mledger.Position;
import org.apache.pulsar.broker.service.persistent.DispatchRateLimiter;
import org.apache.pulsar.broker.service.persistent.SubscribeRateLimiter;
import org.apache.pulsar.broker.service.plugin.EntryFilter;
import org.apache.pulsar.broker.stats.ClusterReplicationMetrics;
import org.apache.pulsar.broker.stats.NamespaceStats;
import org.apache.pulsar.client.api.MessageId;
import org.apache.pulsar.client.api.transaction.TxnID;
import org.apache.pulsar.common.api.proto.CommandSubscribe.InitialPosition;
import org.apache.pulsar.common.api.proto.CommandSubscribe.SubType;
import org.apache.pulsar.common.api.proto.KeySharedMeta;
import org.apache.pulsar.common.policies.data.BacklogQuota;
import org.apache.pulsar.common.policies.data.BacklogQuota.BacklogQuotaType;
import org.apache.pulsar.common.policies.data.EntryFilters;
import org.apache.pulsar.common.policies.data.HierarchyTopicPolicies;
import org.apache.pulsar.common.policies.data.PersistentTopicInternalStats;
import org.apache.pulsar.common.policies.data.Policies;
import org.apache.pulsar.common.policies.data.stats.TopicStatsImpl;
import org.apache.pulsar.common.protocol.schema.SchemaData;
import org.apache.pulsar.common.protocol.schema.SchemaVersion;
import org.apache.pulsar.policies.data.loadbalancer.NamespaceBundleStats;
import org.apache.pulsar.utils.StatsOutputStream;

public interface Topic {

    interface PublishContext {

        default String getProducerName() {
            return null;
        }

        default long getSequenceId() {
            return -1L;
        }

        default void setOriginalProducerName(String originalProducerName) {
        }

        default void setOriginalSequenceId(long originalSequenceId) {
        }

        /**
         * Return the producer name for the original producer.
         * <p>
         * For messages published locally, this will return the same local producer name, though in case of replicated
         * messages, the original producer name will differ
         */
        default String getOriginalProducerName() {
            return null;
        }

        default long getOriginalSequenceId() {
            return -1L;
        }

        void completed(Exception e, long ledgerId, long entryId);

        default void setMetadataFromEntryData(ByteBuf entryData) {
        }

        default long getHighestSequenceId() {
            return  -1L;
        }

        default void setOriginalHighestSequenceId(long originalHighestSequenceId) {

        }

        default long getOriginalHighestSequenceId() {
            return  -1L;
        }

        default long getNumberOfMessages() {
            return  1L;
        }

        default long getMsgSize() {
            return  -1L;
        }

        default boolean isMarkerMessage() {
            return false;
        }

        default void setProperty(String propertyName, Object value) {
        }

        default Object getProperty(String propertyName) {
            return null;
        }

        default boolean isChunked() {
            return false;
        }

        default long getEntryTimestamp() {
            return -1L;
        }

        default void setEntryTimestamp(long entryTimestamp) {

        }

        default boolean supportsReplDedupByLidAndEid() {
            return false;
        }
    }

    CompletableFuture<Void> initialize();

    void publishMessage(ByteBuf headersAndPayload, PublishContext callback);

    /**
     * Tries to add a producer to the topic. Several validations will be performed.
     *
     * @param producer Producer to add
     * @param producerQueuedFuture
     *            a future that will be triggered if the producer is being queued up prior of getting established
     * @return the "topic epoch" if there is one or empty
     */
    CompletableFuture<Optional<Long>> addProducer(Producer producer, CompletableFuture<Void> producerQueuedFuture);

    void removeProducer(Producer producer);

    /**
     * Wait TransactionBuffer recovers completely.
     *
     * @return a future that will be completed after the transaction buffer recover completely.
     */
    CompletableFuture<Void> checkIfTransactionBufferRecoverCompletely();

    /**
     * record add-latency.
     */
    void recordAddLatency(long latency, TimeUnit unit);

    /**
     * increase the publishing limited times.
     */
    long increasePublishLimitedTimes();

    @Deprecated
    CompletableFuture<Consumer> subscribe(TransportCnx cnx, String subscriptionName, long consumerId, SubType subType,
                                          int priorityLevel, String consumerName, boolean isDurable,
                                          MessageId startMessageId,
                                          Map<String, String> metadata, boolean readCompacted,
                                          InitialPosition initialPosition,
                                          long startMessageRollbackDurationSec, boolean replicateSubscriptionState,
                                          KeySharedMeta keySharedMeta);

    /**
     * Subscribe a topic.
     * @param option
     * @return
     */
    CompletableFuture<Consumer> subscribe(SubscriptionOption option);

    CompletableFuture<Subscription> createSubscription(String subscriptionName, InitialPosition initialPosition,
            boolean replicateSubscriptionState, Map<String, String> properties);

    CompletableFuture<Void> unsubscribe(String subName);

    Map<String, ? extends Subscription> getSubscriptions();

    CompletableFuture<Void> delete();

    Map<String, Producer> getProducers();

    String getName();

    CompletableFuture<Void> checkReplication();

    CompletableFuture<Void> close(boolean closeWithoutWaitingClientDisconnect);

    CompletableFuture<Void> close(
            boolean disconnectClients, boolean closeWithoutWaitingClientDisconnect);

    void checkGC();

    CompletableFuture<Void> checkClusterMigration();

    void checkInactiveSubscriptions();

    /**
     * Activate cursors those caught up backlog-threshold entries and deactivate slow cursors which are creating
     * backlog.
     */
    void checkBackloggedCursors();

    void checkCursorsToCacheEntries();

    /**
     * Indicate if the current topic enabled server side deduplication.
     * This is a dynamic configuration, user may update it by namespace/topic policies.
     *
     * @return whether enabled server side deduplication
     */
    default boolean isDeduplicationEnabled() {
        return false;
    }

    void checkDeduplicationSnapshot();

    void checkMessageExpiry();

    void checkMessageDeduplicationInfo();

    void incrementPublishCount(Producer producer, int numOfMessages, long msgSizeInBytes);

    boolean shouldProducerMigrate();

    boolean isReplicationBacklogExist();

    CompletableFuture<Void> onPoliciesUpdate(Policies data);

    CompletableFuture<Void> checkBacklogQuotaExceeded(String producerName, BacklogQuotaType backlogQuotaType);

    boolean isEncryptionRequired();

    boolean getSchemaValidationEnforced();

    boolean isReplicated();

    boolean isShadowReplicated();

    EntryFilters getEntryFiltersPolicy();

    List<EntryFilter> getEntryFilters();

    BacklogQuota getBacklogQuota(BacklogQuotaType backlogQuotaType);

    /**
     * Uses the best-effort (not necessarily up-to-date) information available to return the age.
     * @return The oldest unacknowledged message age in seconds, or -1 if not available
     */
    long getBestEffortOldestUnacknowledgedMessageAgeSeconds();


    void updateRates(NamespaceStats nsStats, NamespaceBundleStats currentBundleStats,
            StatsOutputStream topicStatsStream, ClusterReplicationMetrics clusterReplicationMetrics,
            String namespaceName, boolean hydratePublishers);

    Subscription getSubscription(String subscription);

    Map<String, ? extends Replicator> getReplicators();

    Map<String, ? extends Replicator> getShadowReplicators();

    TopicStatsImpl getStats(boolean getPreciseBacklog, boolean subscriptionBacklogSize,
                            boolean getEarliestTimeInBacklog);

    TopicStatsImpl getStats(GetStatsOptions getStatsOptions);

    CompletableFuture<? extends TopicStatsImpl> asyncGetStats(boolean getPreciseBacklog,
                                                              boolean subscriptionBacklogSize,
                                                              boolean getEarliestTimeInBacklog);

    CompletableFuture<? extends TopicStatsImpl> asyncGetStats(GetStatsOptions getStatsOptions);

    CompletableFuture<PersistentTopicInternalStats> getInternalStats(boolean includeLedgerMetadata);

    Position getLastPosition();

    /**
     * Get the last message position that can be dispatch.
     */
    default CompletableFuture<Position> getLastDispatchablePosition() {
        throw new UnsupportedOperationException("getLastDispatchablePosition is not supported by default");
    }

    CompletableFuture<MessageId> getLastMessageId();

    /**
     * Whether a topic has had a schema defined for it.
     */
    CompletableFuture<Boolean> hasSchema();

    /**
     * Add a schema to the topic. This will fail if the new schema is incompatible with the current
     * schema.
     */
    CompletableFuture<SchemaVersion> addSchema(SchemaData schema);

    /**
     * Delete the schema if this topic has a schema defined for it.
     */
    CompletableFuture<SchemaVersion> deleteSchema();

    /**
     * Check if schema is compatible with current topic schema.
     */
    CompletableFuture<Void> checkSchemaCompatibleForConsumer(SchemaData schema);

    /**
     * If the topic is idle (no producers, no entries, no subscribers and no existing schema),
     * add the passed schema to the topic. Otherwise, check that the passed schema is compatible
     * with what the topic already has.
     */
    CompletableFuture<Void> addSchemaIfIdleOrCheckCompatible(SchemaData schema);

    CompletableFuture<Void> deleteForcefully();

    default Optional<DispatchRateLimiter> getDispatchRateLimiter() {
        return Optional.empty();
    }

    default Optional<SubscribeRateLimiter> getSubscribeRateLimiter() {
        return Optional.empty();
    }

    default Optional<DispatchRateLimiter> getBrokerDispatchRateLimiter() {
        return Optional.empty();
    }

    default boolean isSystemTopic() {
        return false;
    }

    boolean isPersistent();

    boolean isTransferring();

    /* ------ Transaction related ------ */

    /**
     * Publish Transaction message to this Topic's TransactionBuffer.
     *
     * @param txnID             Transaction Id
     * @param headersAndPayload Message data
     * @param publishContext    Publish context
     */
    void publishTxnMessage(TxnID txnID, ByteBuf headersAndPayload, PublishContext publishContext);

    /**
     * End the transaction in this topic.
     *
     * @param txnID Transaction id
     * @param txnAction Transaction action.
     * @param lowWaterMark low water mark of this tc
     * @return
     */
    CompletableFuture<Void> endTxn(TxnID txnID, int txnAction, long lowWaterMark);

    /**
     * Truncate a topic.
     * The truncate operation will move all cursors to the end of the topic and delete all inactive ledgers.
     * @return
     */
    CompletableFuture<Void> truncate();

    /**
     * Get BrokerService.
     * @return
     */
    BrokerService getBrokerService();

    /**
     * Get HierarchyTopicPolicies.
     * @return
     */
    HierarchyTopicPolicies getHierarchyTopicPolicies();

    /**
     * Get OpenTelemetry attribute set.
     * @return
     */
    TopicAttributes getTopicAttributes();
}
