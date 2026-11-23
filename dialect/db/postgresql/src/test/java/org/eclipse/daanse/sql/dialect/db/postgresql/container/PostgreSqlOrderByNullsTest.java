/*
 * Copyright (c) 2026 Contributors to the Eclipse Foundation.
 *
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.daanse.sql.dialect.db.postgresql.container;

import static org.assertj.core.api.Assertions.assertThat;

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
 * PostgreSQL knows {@code ORDER BY … NULLS FIRST | LAST} since 8.3. The dialect is built from a live connection: on
 * every version here it must write that form, not the {@code CASE WHEN … IS NULL} of a database without it, and the
 * server must put the nulls where it was asked to, in each of the four orders, the two that are not PostgreSQL's
 * default among them.
 */
@EnabledIfSystemProperty(named = "integration.docker", matches = "true")
class PostgreSqlOrderByNullsTest {

    private static final String SETUP = """
            CREATE TABLE numbers(v INT);
            INSERT INTO numbers VALUES (2), (NULL), (1), (NULL), (3);
            """;

    @ParameterizedTest(name = "postgres:{0}")
    @ValueSource(strings = { "9.6", "10", "16-alpine" })
    void order_by_nulls(String tag) throws Exception {
        try (PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:" + tag)) {
            pg.start();
            try (Connection c = pg.createConnection(""); Statement st = c.createStatement()) {
                st.execute(SETUP);
                PostgreSqlDialect dialect = new PostgreSqlDialect(DialectInitData.fromConnection(c));

                assertOrder(st, dialect, true, true, "1", "2", "3", null, null);
                assertOrder(st, dialect, true, false, null, null, "1", "2", "3");
                assertOrder(st, dialect, false, true, "3", "2", "1", null, null);
                assertOrder(st, dialect, false, false, null, null, "3", "2", "1");
            }
        }
    }

    private static void assertOrder(Statement st, PostgreSqlDialect dialect, boolean ascending, boolean nullsLast,
            String... expected) throws SQLException {
        String orderBy = dialect.generateOrderByNulls("v", ascending, nullsLast).toString();
        String label = (ascending ? "ASC" : "DESC") + (nullsLast ? " NULLS LAST" : " NULLS FIRST") + ": " + orderBy;

        assertThat(orderBy).as(label).endsWith(nullsLast ? "NULLS LAST" : "NULLS FIRST").doesNotContain("CASE");
        assertThat(values(st, "SELECT v FROM numbers ORDER BY " + orderBy)).as(label).containsExactly(expected);
    }

    private static List<String> values(Statement st, String sql) throws SQLException {
        List<String> out = new ArrayList<>();
        try (ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                out.add(rs.getString(1));
            }
        }
        return out;
    }
}
