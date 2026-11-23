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
import java.util.Optional;

import org.eclipse.daanse.sql.dialect.api.DialectInitData;
import org.eclipse.daanse.sql.dialect.db.postgresql.PostgreSqlDialect;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * PostgreSQL has the ordered-set aggregates {@code percentile_disc} and {@code percentile_cont … WITHIN GROUP} since
 * 9.4. The dialect is built from a live connection: from 9.4 on each percentile it writes must compute the right value
 * on the server, ascending and descending, over a whole table and per group; on 9.3 it must write none, for the server
 * would refuse it.
 */
@EnabledIfSystemProperty(named = "integration.docker", matches = "true")
class PostgreSqlPercentileTest {

    /** Group a: 10, 20, 30, 40 and a NULL that every aggregate leaves out; group b: 5 alone. */
    private static final String SETUP = """
            CREATE TABLE nums(grp VARCHAR(1), v INT);
            INSERT INTO nums VALUES ('a', 30), ('a', 10), ('a', NULL), ('a', 40), ('a', 20), ('b', 5);
            """;

    @ParameterizedTest(name = "postgres:{0} -> {1}")
    @CsvSource({ "9.3, false", "9.4, true", "16-alpine, true" })
    void percentile(String tag, boolean expected) throws Exception {
        try (PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:" + tag)) {
            pg.start();
            try (Connection c = pg.createConnection(""); Statement st = c.createStatement()) {
                st.execute(SETUP);
                PostgreSqlDialect dialect = new PostgreSqlDialect(DialectInitData.fromConnection(c));

                assertThat(dialect.supportsPercentileDisc()).as("supportsPercentileDisc").isEqualTo(expected);
                assertThat(dialect.supportsPercentileCont()).as("supportsPercentileCont").isEqualTo(expected);

                if (!expected) {
                    assertThat(dialect.generatePercentileDisc(0.5, false, "nums", "v")).isEmpty();
                    assertThat(dialect.generatePercentileCont(0.5, false, "nums", "v")).isEmpty();
                    assertThatThrownBy(() -> rows(st,
                            "SELECT percentile_cont(0.5) WITHIN GROUP (ORDER BY v) FROM nums"))
                            .as("PostgreSQL 9.3 knows no WITHIN GROUP").isInstanceOf(SQLException.class)
                            .hasMessageContaining("syntax error");
                    return;
                }

                // discrete: the first value whose cumulative share reaches the percentile, a value of the column
                assertValue(st, dialect.generatePercentileDisc(0.5, false, "nums", "v"), 20);
                assertValue(st, dialect.generatePercentileDisc(0.25, false, "nums", "v"), 10);
                assertValue(st, dialect.generatePercentileDisc(1.0, false, "nums", "v"), 40);
                assertValue(st, dialect.generatePercentileDisc(0.5, true, "nums", "v"), 30);
                assertValue(st, dialect.generatePercentileDisc(0.25, true, null, "v"), 40);

                // continuous: interpolated between the two neighbouring values
                assertValue(st, dialect.generatePercentileCont(0.5, false, "nums", "v"), 25);
                assertValue(st, dialect.generatePercentileCont(0.25, false, "nums", "v"), 17.5);
                assertValue(st, dialect.generatePercentileCont(0.0, false, "nums", "v"), 10);
                assertValue(st, dialect.generatePercentileCont(0.25, true, "nums", "v"), 32.5);
                assertValue(st, dialect.generatePercentileCont(0.5, true, null, "v"), 25);

                String median = dialect.generatePercentileCont(0.5, false, "nums", "v").orElseThrow();
                assertThat(rows(st, "SELECT grp, " + median + " FROM nums GROUP BY grp ORDER BY grp"))
                        .as("per group").containsExactly("a=25.0", "b=5.0");
            }
        }
    }

    private static void assertValue(Statement st, Optional<String> percentile, double expected) throws SQLException {
        assertThat(percentile).isPresent();
        String sql = "SELECT " + percentile.get() + " FROM nums WHERE grp = 'a'";
        try (ResultSet rs = st.executeQuery(sql)) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getDouble(1)).as(sql).isEqualTo(expected);
        }
    }

    private static List<String> rows(Statement st, String sql) throws SQLException {
        List<String> out = new ArrayList<>();
        try (ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                out.add(rs.getString(1) + "=" + rs.getDouble(2));
            }
        }
        return out;
    }
}
