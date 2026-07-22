/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.server.jdbc;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.TestMethodOrder;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

@QuarkusTest
@TestProfile(JdbcPostgresTestProfile.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class JdbcChangeConsumerPostgresIT extends JdbcChangeConsumerIT {

    public static final JdbcPostgresTestUtils JDBC_POSTGRES_TEST_UTILS = new JdbcPostgresTestUtils();

    @Override
    protected JdbcTestUtils testUtils() {
        return JDBC_POSTGRES_TEST_UTILS;
    }
}
