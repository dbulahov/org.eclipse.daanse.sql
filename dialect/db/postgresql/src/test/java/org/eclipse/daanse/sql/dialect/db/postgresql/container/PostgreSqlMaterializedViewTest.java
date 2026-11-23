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
import java.util.Optional;

import org.eclipse.daanse.sql.dialect.api.DialectInitData;
import org.eclipse.daanse.sql.dialect.db.postgresql.PostgreSqlDialect;
import org.eclipse.daanse.sql.model.schema.SchemaReference;
import org.eclipse.daanse.sql.model.schema.TableReference;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * PostgreSQL has materialized views since 9.3, and {@code CREATE MATERIALIZED VIEW IF NOT EXISTS} since 9.5. The
 * dialect is built from a live connection: from 9.3 on the view it creates keeps the rows of its creation until it is
 * refreshed and is dropped again; on 9.3 and 9.4 it asks for {@code IF NOT EXISTS} in vain, and the statement fails on
 * an existing view, as the contract says; on 9.2 it writes no materialized view at all.
 */
@EnabledIfSystemProperty(named = "integration.docker", matches = "true")
class PostgreSqlMaterializedViewTest {

    private static final TableReference TOTALS = new TableReference(
            Optional.of(new SchemaReference(Optional.empty(), "public")), "region_totals", TableReference.TYPE_VIEW);

    private static final String SETUP = """
            CREATE TABLE sales(region VARCHAR(10), amount INT);
            INSERT INTO sales VALUES ('EU', 10), ('EU', 20), ('US', 30);
            """;

    private static final String SELECT = "SELECT region, SUM(amount) AS total FROM sales GROUP BY region";
    private static final String READ = "SELECT region, total FROM region_totals ORDER BY region";

    @ParameterizedTest(name = "postgres:{0} -> materialized views {1}, IF NOT EXISTS {2}")
    @CsvSource({ "9.2, false, false", "9.3, true, false", "9.4, true, false", "9.5, true, true",
            "16-alpine, true, true" })
    void materialized_view(String tag, boolean views, boolean ifNotExists) throws Exception {
        try (PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:" + tag)) {
            pg.start();
            try (Connection c = pg.createConnection(""); Statement st = c.createStatement()) {
                st.execute(SETUP);
                PostgreSqlDialect dialect = new PostgreSqlDialect(DialectInitData.fromConnection(c));

                assertThat(dialect.supportsMaterializedViews()).isEqualTo(views);

                if (!views) {
                    assertThat(dialect.createMaterializedView(TOTALS, SELECT, false)).isEmpty();
                    assertThat(dialect.dropMaterializedView(TOTALS, true)).isEmpty();
                    assertThatThrownBy(() -> st.execute("CREATE MATERIALIZED VIEW mv AS " + SELECT))
                            .as("PostgreSQL 9.2 knows no materialized view").isInstanceOf(SQLException.class)
                            .hasMessageContaining("syntax error");
                    return;
                }

                st.execute(dialect.createMaterializedView(TOTALS, SELECT, false).orElseThrow());
                assertThat(rows(st, READ)).as("the rows of its creation").containsExactly("EU=30", "US=30");

                st.execute("INSERT INTO sales VALUES ('US', 5)");
                assertThat(rows(st, READ)).as("kept until it is refreshed").containsExactly("EU=30", "US=30");
                st.execute("REFRESH MATERIALIZED VIEW region_totals");
                assertThat(rows(st, READ)).as("refreshed").containsExactly("EU=30", "US=35");

                assertThatThrownBy(() -> st.execute(dialect.createMaterializedView(TOTALS, SELECT, false).orElseThrow()))
                        .as("a second one of the name").isInstanceOf(SQLException.class)
                        .hasMessageContaining("already exists");

                String again = dialect.createMaterializedView(TOTALS, SELECT, true).orElseThrow();
                assertThat(again.contains("IF NOT EXISTS")).as(again).isEqualTo(ifNotExists);
                if (ifNotExists) {
                    st.execute(again);
                    assertThat(rows(st, READ)).as("IF NOT EXISTS left it").containsExactly("EU=30", "US=35");
                } else {
                    assertThatThrownBy(() -> st.execute(again)).as("without IF NOT EXISTS it fails on it")
                            .isInstanceOf(SQLException.class).hasMessageContaining("already exists");
                }

                st.execute(dialect.dropMaterializedView(TOTALS, false).orElseThrow());
                assertThatThrownBy(() -> rows(st, READ)).as("dropped").isInstanceOf(SQLException.class)
                        .hasMessageContaining("does not exist");
                st.execute(dialect.dropMaterializedView(TOTALS, true).orElseThrow());
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
