/*
 * Copyright (c) 2026 Contributors to the Eclipse Foundation.
 *
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.daanse.sql.dialect.db.clickhouse.container;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.eclipse.daanse.sql.dialect.api.DialectInitData;
import org.eclipse.daanse.sql.dialect.db.clickhouse.ClickHouseDialect;
import org.eclipse.daanse.sql.model.schema.TableReference;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.testcontainers.clickhouse.ClickHouseContainer;

/**
 * ClickHouse has refreshable materialized views from 24.10 (24.3 and 24.8 refuse one without an experimental setting,
 * 23.8 knows no REFRESH): the view computes its query when it is created, in the background, and keeps the rows until
 * it is refreshed. The dialect is built from a live connection: from 24.10 on the view it creates keeps the rows of
 * its creation, refreshes on demand only, the next refresh years away, fails on a second one of the name, is left as
 * it is with IF NOT EXISTS, and is dropped; before, it writes none. The classic materialized view, on every version,
 * keeps no result of its query: the rows there before it was created are not in it.
 */
@EnabledIfSystemProperty(named = "integration.docker", matches = "true")
class ClickHouseMaterializedViewTest {

    private static final TableReference TOTALS = new TableReference(Optional.empty(), "region_totals",
            TableReference.TYPE_VIEW);

    private static final String SELECT = "SELECT region, sum(amount) AS total FROM sales GROUP BY region";
    private static final String READ = "SELECT concat(region, '=', toString(total)) FROM region_totals"
            + " ORDER BY region";

    @ParameterizedTest(name = "clickhouse:{0} -> materialized views {1}")
    @CsvSource({ "23.8, false", "24.8, false", "24.10, true", "24.12, true", "25.3, true", "25.8, true",
            "26.6, true" })
    void materialized_view(String tag, boolean expected) throws Exception {
        try (ClickHouseContainer ch = ClickHouseContainers.container(tag)) {
            ch.start();
            try (Connection c = DriverManager.getConnection(ClickHouseContainers.jdbcUrl(ch, tag), ch.getUsername(),
                    ch.getPassword()); Statement st = c.createStatement()) {
                st.execute("CREATE TABLE sales(region String, amount Int32) ENGINE = MergeTree() ORDER BY tuple()");
                st.execute("INSERT INTO sales VALUES ('EU', 10), ('EU', 20), ('US', 30)");
                ClickHouseDialect dialect = new ClickHouseDialect(DialectInitData.fromConnection(c));
                assertThat(dialect.supportsMaterializedViews()).as("supportsMaterializedViews").isEqualTo(expected);

                st.execute("CREATE MATERIALIZED VIEW classic ENGINE = MergeTree() ORDER BY tuple() AS " + SELECT);
                assertThat(rows(st, "SELECT toString(count()) FROM classic"))
                        .as("a classic one keeps no result of its query").containsExactly("0");

                if (!expected) {
                    assertThat(dialect.createMaterializedView(TOTALS, SELECT, false)).as("none before 24.10")
                            .isEmpty();
                    assertThat(dialect.dropMaterializedView(TOTALS, true)).as("none before 24.10").isEmpty();
                    assertThatThrownBy(() -> st.execute("CREATE MATERIALIZED VIEW region_totals REFRESH AFTER"
                            + " 10 YEAR ENGINE = MergeTree() ORDER BY tuple() AS " + SELECT))
                            .as("the server refuses a refreshable one").isInstanceOf(SQLException.class);
                    return;
                }

                String create = dialect.createMaterializedView(TOTALS, SELECT, false).orElseThrow();
                assertThat(create).isEqualTo("CREATE MATERIALIZED VIEW " + dialect.quoteIdentifier("region_totals")
                        + " REFRESH AFTER 10 YEAR ENGINE = MergeTree() ORDER BY tuple() AS " + SELECT);
                execute(st, create);
                st.execute("SYSTEM WAIT VIEW region_totals");
                assertThat(rows(st, READ)).as("the rows of its creation").containsExactly("EU=30", "US=30");

                st.execute("INSERT INTO sales VALUES ('US', 5)");
                assertThat(rows(st, READ)).as("kept until it is refreshed").containsExactly("EU=30", "US=30");
                assertThat(rows(st, "SELECT toString(toYear(next_refresh_time) - toYear(now()) >= 9)"
                        + " FROM system.view_refreshes WHERE view = 'region_totals'"))
                        .as("the next refresh years away").containsExactly("1");
                st.execute("SYSTEM REFRESH VIEW region_totals");
                st.execute("SYSTEM WAIT VIEW region_totals");
                assertThat(rows(st, READ)).as("refreshed").containsExactly("EU=30", "US=35");

                assertThatThrownBy(() -> st.execute(create)).as("a second one of the name")
                        .isInstanceOf(SQLException.class);
                execute(st, dialect.createMaterializedView(TOTALS, "SELECT 'x' AS region, 1 AS total", true)
                        .orElseThrow());
                assertThat(rows(st, READ)).as("IF NOT EXISTS left it").containsExactly("EU=30", "US=35");

                execute(st, dialect.dropMaterializedView(TOTALS, false).orElseThrow());
                assertThatThrownBy(() -> rows(st, READ)).as("dropped").isInstanceOf(SQLException.class);
                assertThatThrownBy(() -> st.execute(dialect.dropMaterializedView(TOTALS, false).orElseThrow()))
                        .as("a missing one").isInstanceOf(SQLException.class);
                execute(st, dialect.dropMaterializedView(TOTALS, true).orElseThrow());
            }
        }
    }

    private static void execute(Statement st, String sql) throws SQLException {
        try {
            st.execute(sql);
        } catch (SQLException e) {
            throw new SQLException(e.getMessage() + " in " + sql, e);
        }
    }

    private static List<String> rows(Statement st, String sql) throws SQLException {
        List<String> out = new ArrayList<>();
        try (ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                out.add(rs.getString(1));
            }
        }
        return out;
    }
}
