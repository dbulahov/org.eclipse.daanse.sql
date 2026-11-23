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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.daanse.sql.dialect.api.DialectInitData;
import org.eclipse.daanse.sql.dialect.api.generator.KnownFunction;
import org.eclipse.daanse.sql.dialect.db.postgresql.PostgreSqlDialect;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * PostgreSQL has {@code make_date(year, month, day)} since 9.4. The dialect is built from a live connection: from 9.4
 * on it writes {@code make_date}, before that the ISO text {@code yyyy-mm-dd} read as a date, and either way the
 * server must make the right date, of literals and of columns of each numeric type, a NULL of a NULL part, and an
 * error of a day the month does not have.
 */
@EnabledIfSystemProperty(named = "integration.docker", matches = "true")
class PostgreSqlDateFromPartsTest {

    /** Each part of another type: make_date takes integers, so a smallint and a numeric part must be cast. */
    private static final String SETUP = """
            CREATE TABLE parts(id INT, y SMALLINT, m INTEGER, d NUMERIC(2));
            INSERT INTO parts VALUES (1, 2026, 11, 23), (2, 2024, 2, 29), (3, 1999, 1, 5), (4, 2026, NULL, 1);
            """;

    @ParameterizedTest(name = "postgres:{0} -> make_date {1}")
    @CsvSource({ "9.3, false", "9.4, true", "16-alpine, true" })
    void date_from_parts(String tag, boolean makeDate) throws Exception {
        try (PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:" + tag)) {
            pg.start();
            try (Connection c = pg.createConnection(""); Statement st = c.createStatement()) {
                st.execute(SETUP);
                PostgreSqlDialect dialect = new PostgreSqlDialect(DialectInitData.fromConnection(c));

                String columns = dateFromParts(dialect, "y", "m", "d");
                assertThat(columns.startsWith("make_date(")).as(columns).isEqualTo(makeDate);
                if (!makeDate) {
                    assertThat(columns).doesNotContainIgnoringCase("make_date");
                    assertThatThrownBy(() -> dates(st, "SELECT make_date(2026, 1, 1)"))
                            .as("PostgreSQL 9.3 knows no make_date").isInstanceOf(SQLException.class)
                            .hasMessageContaining("does not exist");
                }

                assertThat(dates(st, "SELECT " + columns + " FROM parts ORDER BY id")).as("of columns: " + columns)
                        .containsExactly("2026-11-23", "2024-02-29", "1999-01-05", null);

                String literals = dateFromParts(dialect, "2026", "1", "9");
                assertThat(dates(st, "SELECT " + literals)).as("of literals, month and day of one digit: " + literals)
                        .containsExactly("2026-01-09");

                String noSuchDay = dateFromParts(dialect, "2023", "2", "29");
                assertThatThrownBy(() -> dates(st, "SELECT " + noSuchDay)).as("29 February 2023: " + noSuchDay)
                        .isInstanceOf(SQLException.class).hasMessageContaining("out of range");

                assertThatThrownBy(() -> dialect.generateKnownFunction(KnownFunction.DATE_FROM_PARTS,
                        List.of("2026", "1"))).isInstanceOf(IllegalArgumentException.class);
            }
        }
    }

    private static String dateFromParts(PostgreSqlDialect dialect, String year, String month, String day) {
        return dialect.generateKnownFunction(KnownFunction.DATE_FROM_PARTS, List.of(year, month, day)).toString();
    }

    private static List<String> dates(Statement st, String sql) throws SQLException {
        List<String> out = new ArrayList<>();
        try (ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                out.add(rs.getString(1));
            }
        }
        return out;
    }
}
