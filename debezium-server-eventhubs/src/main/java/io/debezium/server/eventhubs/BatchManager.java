/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.server.eventhubs;

import java.util.HashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.azure.messaging.eventhubs.EventData;
import com.azure.messaging.eventhubs.EventHubProducerClient;
import com.azure.messaging.eventhubs.models.CreateBatchOptions;

import io.debezium.DebeziumException;
import io.debezium.util.Strings;

public class BatchManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(BatchManager.class);
    private final EventHubProducerClient producer;
    private final String configuredPartitionId;
    private final String configuredPartitionKey;
    private final Integer maxBatchSize;

    static final Integer BATCH_INDEX_FOR_NO_PARTITION_ID = -1;
    static final Integer BATCH_INDEX_FOR_PARTITION_KEY = 0;

    // Prepare CreateBatchOptions for N partitions
    private final HashMap<Integer, CreateBatchOptions> batchOptions = new HashMap<>();
    private final HashMap<Integer, EventDataBatchProxy> batches = new HashMap<>();

    private final HashMap<String, EventDataBatchProxy> dynamicPartitionKeyBatches = new HashMap<>();

    public BatchManager(EventHubProducerClient producer, String configurePartitionId,
                        String configuredPartitionKey, Integer maxBatchSize) {
        this.producer = producer;
        this.configuredPartitionId = configurePartitionId;
        this.configuredPartitionKey = configuredPartitionKey;
        this.maxBatchSize = maxBatchSize;
    }

    public void initializeBatch() {
        if (!configuredPartitionId.isEmpty() || !configuredPartitionKey.isEmpty()) {
            CreateBatchOptions op = new CreateBatchOptions();

            if (!configuredPartitionId.isEmpty()) {
                op.setPartitionId(configuredPartitionId);

                batchOptions.put(Integer.parseInt(configuredPartitionId), op);
                batches.put(Integer.parseInt(configuredPartitionId), new EventDataBatchProxy(producer, op));
            }
            else if (!configuredPartitionKey.isEmpty()) {
                op.setPartitionKey(configuredPartitionKey);

                batchOptions.put(BATCH_INDEX_FOR_PARTITION_KEY, op);
                batches.put(BATCH_INDEX_FOR_PARTITION_KEY, new EventDataBatchProxy(producer, op));
            }

            if (maxBatchSize != 0) {
                op.setMaximumSizeInBytes(maxBatchSize);
            }

            return;
        }

        // Prepare batch for messages without partition id
        CreateBatchOptions op = new CreateBatchOptions();
        if (maxBatchSize != 0) {
            op.setMaximumSizeInBytes(maxBatchSize);
        }
        batchOptions.put(BATCH_INDEX_FOR_NO_PARTITION_ID, op);

        producer.getPartitionIds().stream().forEach(partitionId -> {
            CreateBatchOptions createBatchOptionsForPartitionId = new CreateBatchOptions().setPartitionId(partitionId);
            if (maxBatchSize != 0) {
                createBatchOptionsForPartitionId.setMaximumSizeInBytes(maxBatchSize);
            }
            batchOptions.put(Integer.parseInt(partitionId), createBatchOptionsForPartitionId);
        });

        // Prepare all EventDataBatchProxies
        batchOptions.forEach((batchIndex, createBatchOptions) -> {
            EventDataBatchProxy batch = new EventDataBatchProxy(producer, createBatchOptions);
            batches.put(batchIndex, batch);
        });

    }

    public void closeAndEmitBatches() {
        // All records have been processed, emit the final (non-full) batches.
        batches.forEach((partitionId, batch) -> {
            if (batch.getCount() > 0) {
                LOGGER.trace("Dispatching {} events.", batch.getCount());
                emitBatchToEventHub(batch);
            }
        });

        dynamicPartitionKeyBatches.forEach((partitionKey, batch) -> {
            if (batch.getCount() > 0) {
                LOGGER.trace("Dispatching {} events for partition key '{}'.", batch.getCount(), partitionKey);
                emitBatchToEventHub(batch);
            }
        });
    }

    public void sendEventToPartitionId(EventData eventData, Integer recordIndex, Integer partitionId) {
        EventDataBatchProxy batch = batches.get(partitionId);

        if (!batch.tryAdd(eventData)) {
            if (batch.getCount() == 0) {
                // If we fail to add at least the very first event to the batch that is because
                // the event's size exceeds the maxBatchSize in which case we cannot safely
                // recover and dispatch the event, only option is to throw an exception.
                throw new DebeziumException("Event data is too large to fit into batch");
            }
            // reached the maximum allowed size for the batch
            LOGGER.debug("Maximum batch size reached, dispatching {} events.", batch.getCount());

            // Max size reached, dispatch the batch to EventHub
            emitBatchToEventHub(batch);
            // Renew the batch proxy so we can continue.
            batch = new EventDataBatchProxy(producer, batchOptions.get(partitionId));
            batches.put(partitionId, batch);
            // Add event which we failed to add to the previous batch which was already full.
            if (!batch.tryAdd(eventData)) {
                // This is the first event in the batch, if we failed to add it, it has to be too large.
                throw new DebeziumException("Event data is too large to fit into batch");
            }
        }
    }

    /**
     * Sends an event using a dynamic partition key derived from the record key.
     * This allows proper partitioning when no explicit partition ID or key is configured.
     */
    public void sendEventWithDynamicPartitionKey(EventData eventData, String partitionKey) {
        String effectivePartitionKey = Strings.isNullOrBlank(partitionKey) ? "default" : partitionKey;

        EventDataBatchProxy batch = dynamicPartitionKeyBatches.get(effectivePartitionKey);

        if (batch == null) {
            CreateBatchOptions op = new CreateBatchOptions().setPartitionKey(effectivePartitionKey);
            if (maxBatchSize != 0) {
                op.setMaximumSizeInBytes(maxBatchSize);
            }
            batch = new EventDataBatchProxy(producer, op);
            dynamicPartitionKeyBatches.put(effectivePartitionKey, batch);
        }

        if (!batch.tryAdd(eventData)) {
            if (batch.getCount() == 0) {
                throw new DebeziumException("Event data is too large to fit into batch");
            }

            LOGGER.debug("Maximum batch size reached for partition key '{}', dispatching {} events.", effectivePartitionKey, batch.getCount());

            // Max size reached, dispatch the batch to EventHub
            emitBatchToEventHub(batch);

            // Create a new batch proxy so we can continue.
            CreateBatchOptions op = new CreateBatchOptions().setPartitionKey(effectivePartitionKey);
            if (maxBatchSize != 0) {
                op.setMaximumSizeInBytes(maxBatchSize);
            }
            batch = new EventDataBatchProxy(producer, op);
            dynamicPartitionKeyBatches.put(effectivePartitionKey, batch);

            // Add event which we failed to add to the previous batch which was already full.
            if (!batch.tryAdd(eventData)) {
                throw new DebeziumException("Event data is too large to fit into batch");
            }
        }
    }

    private void emitBatchToEventHub(EventDataBatchProxy batch) {
        final int batchEventSize = batch.getCount();
        if (batchEventSize > 0) {
            try {
                LOGGER.trace("Sending batch of {} events to Event Hubs", batchEventSize);
                batch.emit();
                LOGGER.trace("Sent record batch to Event Hubs");
            }
            catch (Exception e) {
                throw new DebeziumException(e);
            }
        }
    }
}
