/*
 * Copyright (c) 2026 Contributors to the Eclipse Foundation.
 *
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.daanse.sql.dialect.db.mssqlserver.container;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.daanse.sql.dialect.api.DialectInitData;
import org.eclipse.daanse.sql.dialect.db.mssqlserver.MicrosoftSqlServerDialect;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.containers.MSSQLServerContainer;

/**
 * SQL Server has {@code PERCENTILE_DISC} and {@code PERCENTILE_CONT … WITHIN GROUP} only as window functions, in every
 * version Docker serves, 2017 to 2025: as an aggregate the server refuses them, "must have an OVER clause" (Msg
 * 10753), in a grouped query too. The dialect is built from a live connection: it offers no percentile and writes none,
 * so a measure of one is refused rather than sent to the server. The window form works, over a partition of the rows,
 * which a grouped query cannot take for its groups.
 */
@EnabledIfSystemProperty(named = "integration.docker", matches = "true")
class MsSqlPercentileTest {

    /** Group a: 10, 20, 30, 40 and a NULL that every percentile leaves out; group b: 5 alone. */
    private static final List<String> SETUP = List.of("CREATE TABLE nums(g VARCHAR(1), v INT)",
            "INSERT INTO nums VALUES ('a', 30), ('a', 10), ('a', NULL), ('a', 40), ('a', 20), ('b', 5)");

    @ParameterizedTest(name = "mssql/server:{0}")
    @ValueSource(strings = { "2017-latest", "2019-latest", "2022-latest", "2025-latest" })
    void percentile(String tag) throws Exception {
        try (MSSQLServerContainer<?> ms = new MSSQLServerContainer<>("mcr.microsoft.com/mssql/server:" + tag)
                .acceptLicense()) {
            ms.start();
            try (Connection c = DriverManager.getConnection(ms.getJdbcUrl(), ms.getUsername(), ms.getPassword());
                    Statement st = c.createStatement()) {
                for (String sql : SETUP) {
                    st.execute(sql);
                }
                MicrosoftSqlServerDialect dialect = new MicrosoftSqlServerDialect(DialectInitData.fromConnection(c));

                assertThat(dialect.supportsPercentileDisc()).as("supportsPercentileDisc").isFalse();
                assertThat(dialect.supportsPercentileCont()).as("supportsPercentileCont").isFalse();
                assertThat(dialect.generatePercentileDisc(0.5, false, "nums", "v")).as("no PERCENTILE_DISC").isEmpty();
                assertThat(dialect.generatePercentileDisc(0.5, true, null, "v")).as("no PERCENTILE_DISC").isEmpty();
                assertThat(dialect.generatePercentileCont(0.5, false, "nums", "v")).as("no PERCENTILE_CONT").isEmpty();
                assertThat(dialect.generatePercentileCont(0.25, true, null, "v")).as("no PERCENTILE_CONT").isEmpty();

                for (String f : List.of("PERCENTILE_DISC", "PERCENTILE_CONT")) {
                    assertThatThrownBy(() -> rows(st, "SELECT " + f + "(0.5) WITHIN GROUP (ORDER BY v) FROM nums"))
                            .as(f + " as an aggregate").isInstanceOf(SQLException.class)
                            .hasMessageContaining("must have an OVER clause");
                    assertThatThrownBy(() -> rows(st,
                            "SELECT g, " + f + "(0.5) WITHIN GROUP (ORDER BY v) FROM nums GROUP BY g"))
                            .as(f + " in a grouped query").isInstanceOf(SQLException.class)
                            .hasMessageContaining("must have an OVER clause");
                }

                assertThat(rows(st, "SELECT DISTINCT g, PERCENTILE_DISC(0.5) WITHIN GROUP (ORDER BY v)"
                        + " OVER (PARTITION BY g), PERCENTILE_CONT(0.25) WITHIN GROUP (ORDER BY v DESC)"
                        + " OVER (PARTITION BY g) FROM nums ORDER BY g"))
                        .as("the window form, over a partition").containsExactly("a|20|32.5", "b|5|5.0");
            }
        }
    }

    private static List<String> rows(Statement st, String sql) throws SQLException {
        List<String> out = new ArrayList<>();
        try (ResultSet rs = st.executeQuery(sql)) {
            int n = rs.getMetaData().getColumnCount();
            while (rs.next()) {
                StringBuilder row = new StringBuilder();
                for (int i = 1; i <= n; i++) {
                    if (i > 1)
                        row.append('|');
                    row.append(rs.getString(i));
                }
                out.add(row.toString());
            }
        }
        return out;
    }
}
