/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.server.qdrant;

import java.util.HashMap;
import java.util.Map;

import org.apache.kafka.connect.runtime.standalone.StandaloneConfig;

import io.debezium.server.TestConfigSource;

public class QdrantTestConfigSource extends TestConfigSource {

    public QdrantTestConfigSource() {
        Map<String, String> qdrantTest = new HashMap<>();

        qdrantTest.put("debezium.sink.type", "qdrant");
        qdrantTest.put("debezium.source.connector.class", "io.debezium.connector.postgresql.PostgresConnector");
        qdrantTest.put("debezium.source." + StandaloneConfig.OFFSET_STORAGE_FILE_FILENAME_CONFIG,
                OFFSET_STORE_PATH.toAbsolutePath().toString());
        qdrantTest.put("debezium.source.offset.flush.interval.ms", "0");
        qdrantTest.put("debezium.source.topic.prefix", "testc");
        qdrantTest.put("debezium.source.schema.include.list", "inventory");
        qdrantTest.put("debezium.source.table.include.list", "inventory.t_vector");

        config = qdrantTest;
    }

    @Override
    public int getOrdinal() {
        // Configuration property precedence is based on ordinal values and since we override the
        // properties in TestConfigSource, we should give this a higher priority.
        return super.getOrdinal() + 1;
    }
}
