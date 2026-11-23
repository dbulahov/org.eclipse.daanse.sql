/*
 * Copyright (c) 2026 Contributors to the Eclipse Foundation.
 *
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.daanse.sql.dialect.db.postgresql.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.daanse.sql.dialect.api.DialectInitData;
import org.eclipse.daanse.sql.dialect.db.postgresql.PostgreSqlDialect;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Starts a PostgreSQL of a given version in Docker and runs a SELECT on it. The dialect is built from the live
 * connection, so its {@code supportsGroupingSets()} is checked against what the server really does.
 */
//@EnabledIfSystemProperty(named = "integration.docker", matches = "true")
class PostgreSqlVersionSelectTest {

    private static final String SETUP = """
            CREATE TABLE sales(region VARCHAR(10), product VARCHAR(10), amount INT);
            INSERT INTO sales VALUES ('EU','A',10), ('EU','B',20), ('US','A',30);
            """;

    private static final String GROUPING_SETS_SELECT = """
            SELECT region, SUM(amount) FROM sales
            GROUP BY GROUPING SETS ((region), ())
            ORDER BY region NULLS LAST
            """;

    @ParameterizedTest(name = "postgres:{0}")
    @ValueSource(strings = { "9.4", "9.6", "12-alpine", "16-alpine" })
    void select_on_postgres_version(String tag) throws Exception {
        try (PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:" + tag)) {
            pg.start();
            try (Connection c = pg.createConnection(""); Statement st = c.createStatement()) {

                try (ResultSet rs = st.executeQuery("SELECT 1")) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getInt(1)).isEqualTo(1);
                }

                st.execute(SETUP);
                PostgreSqlDialect dialect = new PostgreSqlDialect(DialectInitData.fromConnection(c));

                if (dialect.supportsGroupingSets()) {
                    assertThat(rows(st, GROUPING_SETS_SELECT)).containsExactly("EU=30", "US=30", "null=60");
                } else {
                    assertThatThrownBy(() -> st.executeQuery(GROUPING_SETS_SELECT)).isInstanceOf(SQLException.class);
                }
            }
        }
    }

    private static List<String> rows(Statement st, String sql) throws SQLException {
        List<String> out = new ArrayList<>();
        try (ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                out.add(rs.getString(1) + "=" + rs.getInt(2));
            }
        }
        return out;
    }
}
