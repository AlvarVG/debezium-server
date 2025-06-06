/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.server.redis;

import static io.debezium.server.redis.RedisStreamChangeConsumerConfig.MESSAGE_FORMAT_COMPACT;
import static io.debezium.server.redis.RedisStreamChangeConsumerConfig.MESSAGE_FORMAT_EXTENDED;

import java.time.Duration;
import java.util.AbstractMap.SimpleEntry;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.Dependent;
import jakarta.inject.Named;

import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.debezium.DebeziumException;
import io.debezium.config.Configuration;
import io.debezium.engine.ChangeEvent;
import io.debezium.engine.DebeziumEngine;
import io.debezium.engine.DebeziumEngine.RecordCommitter;
import io.debezium.server.BaseChangeConsumer;
import io.debezium.storage.redis.RedisClient;
import io.debezium.storage.redis.RedisClientConnectionException;
import io.debezium.storage.redis.RedisConnection;
import io.debezium.util.DelayStrategy;

/**
 * Implementation of the consumer that delivers the messages into Redis (stream)
 * destination.
 *
 * @author M Sazzadul Hoque
 * @author Yossi Shirizli
 */
@Named("redis")
@Dependent
public class RedisStreamChangeConsumer extends BaseChangeConsumer
        implements DebeziumEngine.ChangeConsumer<ChangeEvent<Object, Object>> {

    private static final Logger LOGGER = LoggerFactory.getLogger(RedisStreamChangeConsumer.class);

    private static final String DEBEZIUM_REDIS_SINK_CLIENT_NAME = "debezium:redis:sink";

    private static final String HEARTBEAT_PREFIX_CONFIG = "topic.heartbeat.prefix";
    private static final String DEFAULT_HEARTBEAT_PREFIX = "__debezium-heartbeat";

    private static final String EXTENDED_MESSAGE_KEY_KEY = "key";
    private static final String EXTENDED_MESSAGE_VALUE_KEY = "value";
    private RedisClient client;

    private Function<ChangeEvent<Object, Object>, Map<String, String>> recordMapFunction;

    private RedisMemoryThreshold redisMemoryThreshold;

    private RedisStreamChangeConsumerConfig config;

    private String heartbeatPrefix;

    @PostConstruct
    void connect() {
        // Get configuration from ConfigProvider
        Config mpConfig = ConfigProvider.getConfig();
        Map<String, Object> sourceConfig = getConfigSubset(mpConfig, "debezium.source.");

        // Get Redis sink configuration
        Configuration configuration = Configuration.from(getConfigSubset(mpConfig, ""));
        config = new RedisStreamChangeConsumerConfig(configuration);

        // Get the heartbeat prefix from the configuration
        heartbeatPrefix = (String) sourceConfig.getOrDefault(HEARTBEAT_PREFIX_CONFIG, DEFAULT_HEARTBEAT_PREFIX);
        LOGGER.info("Using heartbeat prefix: {}", heartbeatPrefix);

        String messageFormat = config.getMessageFormat();
        if (MESSAGE_FORMAT_EXTENDED.equals(messageFormat)) {
            recordMapFunction = record -> {
                Map<String, String> recordMap = new LinkedHashMap<>();
                String key = (record.key() != null) ? getString(record.key()) : config.getNullKey();
                String value = (record.value() != null) ? getString(record.value()) : config.getNullValue();
                Map<String, String> headers = convertHeaders(record);

                recordMap.put(EXTENDED_MESSAGE_KEY_KEY, key);
                recordMap.put(EXTENDED_MESSAGE_VALUE_KEY, value);
                for (Map.Entry<String, String> entry : headers.entrySet()) {
                    recordMap.put(entry.getKey().toUpperCase(Locale.ROOT), entry.getValue());
                }
                return recordMap;
            };
        }
        else if (MESSAGE_FORMAT_COMPACT.equals(messageFormat)) {
            recordMapFunction = record -> {
                String key = (record.key() != null) ? getString(record.key()) : config.getNullKey();
                String value = (record.value() != null) ? getString(record.value()) : config.getNullValue();
                return Map.of(key, value);
            };
        }

        RedisConnection redisConnection = RedisConnection.getInstance(config);
        client = redisConnection.getRedisClient(DEBEZIUM_REDIS_SINK_CLIENT_NAME, config.isWaitEnabled(),
                config.getWaitTimeout(), config.isWaitRetryEnabled(), config.getWaitRetryDelay());

        redisMemoryThreshold = new RedisMemoryThreshold(client, config);
    }

    @PreDestroy
    void close() {
        try {
            if (client != null) {
                client.close();
            }
        }
        catch (Exception e) {
            LOGGER.warn("Exception while closing Jedis: {}", client, e);
        }
        finally {
            client = null;
        }
    }

    /**
     * Split collection to batches by batch size using a stream
     */
    private <T> Stream<List<T>> batches(List<T> source, int length) {
        if (source.isEmpty()) {
            return Stream.empty();
        }

        int size = source.size();
        int fullChunks = (size - 1) / length;

        return IntStream.range(0, fullChunks + 1)
                .mapToObj(n -> source.subList(n * length, n == fullChunks ? size : (n + 1) * length));
    }

    @Override
    public void handleBatch(List<ChangeEvent<Object, Object>> records,
                            RecordCommitter<ChangeEvent<Object, Object>> committer)
            throws InterruptedException {
        DelayStrategy delayStrategy = DelayStrategy.exponential(Duration.ofMillis(config.getInitialRetryDelay()),
                Duration.ofMillis(config.getMaxRetryDelay()));

        DelayStrategy delayStrategyOnRecordsConsumption = DelayStrategy.constant(Duration.ofMillis(config.getWaitRetryDelay()));

        LOGGER.debug("Handling a batch of {} records", records.size());
        batches(records, config.getBatchSize()).forEach(batch -> {
            boolean completedSuccessfully = false;

            // Clone the batch and remove the records that have been successfully processed.
            // Move to the next batch once this list is empty.
            List<ChangeEvent<Object, Object>> clonedBatch = batch.stream().collect(Collectors.toList());

            // As long as we failed to execute the current batch to the stream, we should
            // retry if the reason
            // was either a connection error or OOM in Redis.
            while (!completedSuccessfully) {
                if (client == null) {
                    // Try to reconnect
                    try {
                        connect();
                        continue; // Managed to establish a new connection to Redis, avoid a redundant retry
                    }
                    catch (Exception e) {
                        close();
                        LOGGER.error("Can't connect to Redis", e);
                    }
                }
                else {
                    try {
                        LOGGER.debug("Preparing a Redis Pipeline of {} records", clonedBatch.size());

                        List<SimpleEntry<String, Map<String, String>>> recordsMap = new ArrayList<>(clonedBatch.size());
                        for (ChangeEvent<Object, Object> record : clonedBatch) {
                            String destination = streamNameMapper.map(record.destination());

                            // Check if this is a heartbeat message that should be skipped
                            if (config.isSkipHeartbeatMessages() && destination.startsWith(heartbeatPrefix)) {
                                // Mark as processed but don't add to Redis
                                committer.markProcessed(record);
                                continue;
                            }

                            Map<String, String> recordMap = recordMapFunction.apply(record);
                            recordsMap.add(new SimpleEntry<>(destination, recordMap));
                        }

                        if (recordsMap.size() == 0) {
                            continue;
                        }

                        if (!redisMemoryThreshold.checkMemory(getObjectSize(recordsMap.get(0)), recordsMap.size(),
                                config.getBufferFillRate())) {
                            LOGGER.info("Stopped consuming records!\n");
                            delayStrategyOnRecordsConsumption.sleepWhen(true);
                            continue;
                        }
                        List<String> responses = client.xadd(recordsMap);
                        List<ChangeEvent<Object, Object>> processedRecords = new ArrayList<ChangeEvent<Object, Object>>();
                        int index = 0;
                        int totalOOMResponses = 0;

                        for (String message : responses) {
                            // When Redis reaches its max memory limitation, an OOM error message will be
                            // retrieved.
                            // In this case, we will retry execute the failed commands, assuming some memory
                            // will be freed eventually as result
                            // of evicting elements from the stream by the target DB.
                            if (message.contains("OOM command not allowed when used memory > 'maxmemory'")) {
                                totalOOMResponses++;
                            }
                            else {
                                // Mark the record as processed
                                ChangeEvent<Object, Object> currentRecord = clonedBatch.get(index);
                                committer.markProcessed(currentRecord);
                                processedRecords.add(currentRecord);
                            }

                            index++;
                        }

                        clonedBatch.removeAll(processedRecords);

                        if (totalOOMResponses > 0) {
                            LOGGER.info("Redis sink currently full, will retry ({} command(s) will be retried)",
                                    totalOOMResponses);
                        }

                        if (clonedBatch.size() == 0) {
                            completedSuccessfully = true;
                        }
                    }
                    catch (RedisClientConnectionException jce) {
                        LOGGER.error("Connection error", jce);
                        close();
                    }
                    catch (Exception e) {
                        LOGGER.error("Unexpected Exception", e);
                        throw new DebeziumException(e);
                    }
                }

                // Failed to execute the transaction, retry...
                delayStrategy.sleepWhen(!completedSuccessfully);
            }
        });

        // Mark the whole batch as finished once the sub batches completed
        committer.markBatchFinished();
    }

    private static long getObjectSize(SimpleEntry<String, Map<String, String>> record) {
        long approximateSize = 0;
        approximateSize += record.getKey().getBytes().length;
        Map<String, String> value = record.getValue();
        for (Map.Entry<String, String> entry : value.entrySet()) {
            approximateSize += entry.getKey().getBytes().length +
                    entry.getValue().getBytes().length;
        }
        LOGGER.debug("Estimated record size is {}", approximateSize);
        return approximateSize;
    }
}
