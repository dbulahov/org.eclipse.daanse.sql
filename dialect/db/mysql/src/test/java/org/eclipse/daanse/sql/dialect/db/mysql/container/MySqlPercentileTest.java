/*
 * Copyright (c) 2026 Contributors to the Eclipse Foundation.
 *
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.daanse.sql.dialect.db.mysql.container;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Optional;

import org.eclipse.daanse.sql.dialect.api.DialectInitData;
import org.eclipse.daanse.sql.dialect.db.mysql.MySqlDialect;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.testcontainers.containers.MySQLContainer;

/**
 * MySQL has no {@code PERCENTILE_DISC} and no {@code PERCENTILE_CONT … WITHIN GROUP}, as an aggregate or otherwise. The
 * dialect is built from a live connection: where it offers a percentile, the server must compute the right value of
 * it; where the server has none, the dialect must offer none and write none.
 */
@EnabledIfSystemProperty(named = "integration.docker", matches = "true")
class MySqlPercentileTest {

    /** 10, 20, 30, 40 and a NULL that every aggregate leaves out. */
    private static final String CREATE = "CREATE TABLE nums(v INT)";
    private static final String INSERT = "INSERT INTO nums VALUES (30), (10), (NULL), (40), (20)";

    @ParameterizedTest(name = "mysql:{0} -> percentiles {1}")
    @CsvSource({ "5.7, false", "8.0, false", "8.4, false", "9, false" })
    void percentile(String tag, boolean expected) throws Exception {
        // the server's own configuration: Testcontainers' default sets innodb_log_file_size, which MySQL 9 refuses
        try (MySQLContainer<?> my = new MySQLContainer<>("mysql:" + tag).withConfigurationOverride("mysql-conf")
                .withDatabaseName("rt").withUsername("rt").withPassword("rt")) {
            my.start();
            try (Connection c = DriverManager.getConnection(my.getJdbcUrl(), my.getUsername(), my.getPassword());
                    Statement st = c.createStatement()) {
                st.execute(CREATE);
                st.execute(INSERT);
                MySqlDialect dialect = new MySqlDialect(DialectInitData.fromConnection(c));

                assertThat(dialect.supportsPercentileDisc()).as("supportsPercentileDisc").isEqualTo(expected);
                assertThat(dialect.supportsPercentileCont()).as("supportsPercentileCont").isEqualTo(expected);

                if (!expected) {
                    assertThat(dialect.generatePercentileDisc(0.5, false, "nums", "v")).isEmpty();
                    assertThat(dialect.generatePercentileCont(0.5, false, "nums", "v")).isEmpty();
                    assertThatThrownBy(() -> value(st, "SELECT PERCENTILE_CONT(0.5) WITHIN GROUP (ORDER BY v) FROM nums"))
                            .as("the server knows no PERCENTILE_CONT").isInstanceOf(SQLException.class);
                    assertThatThrownBy(() -> value(st, "SELECT PERCENTILE_DISC(0.5) WITHIN GROUP (ORDER BY v) FROM nums"))
                            .as("the server knows no PERCENTILE_DISC").isInstanceOf(SQLException.class);
                    return;
                }

                assertValue(st, dialect.generatePercentileDisc(0.5, false, "nums", "v"), 20);
                assertValue(st, dialect.generatePercentileDisc(0.5, true, "nums", "v"), 30);
                assertValue(st, dialect.generatePercentileCont(0.5, false, "nums", "v"), 25);
                assertValue(st, dialect.generatePercentileCont(0.25, false, null, "v"), 17.5);
            }
        }
    }

    private static void assertValue(Statement st, Optional<String> percentile, double expected) throws SQLException {
        assertThat(percentile).isPresent();
        assertThat(value(st, "SELECT " + percentile.get() + " FROM nums")).as(percentile.get()).isEqualTo(expected);
    }

    private static double value(Statement st, String sql) throws SQLException {
        try (ResultSet rs = st.executeQuery(sql)) {
            assertThat(rs.next()).isTrue();
            return rs.getDouble(1);
        }
    }
}
