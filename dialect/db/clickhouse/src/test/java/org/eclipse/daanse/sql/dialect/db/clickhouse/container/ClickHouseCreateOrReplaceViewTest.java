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
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.clickhouse.ClickHouseContainer;

/**
 * ClickHouse takes {@code CREATE OR REPLACE VIEW} on every version here, but replaces whatever is there under the
 * name, a table too, which it drops with its rows; MySQL, Oracle and PostgreSQL refuse to replace anything but a view.
 * The dialect is built from a live connection and says it has no {@code CREATE OR REPLACE VIEW}: with
 * {@code orReplace} it writes a plain {@code CREATE VIEW}, which creates the view where there is none, fails on a
 * view of the name, and on a table of the name, which keeps its rows.
 */
@EnabledIfSystemProperty(named = "integration.docker", matches = "true")
class ClickHouseCreateOrReplaceViewTest {

    private static final TableReference V = new TableReference(Optional.empty(), "v", TableReference.TYPE_VIEW);

    private static final List<String> SETUP = List.of("CREATE TABLE t(x Int32) ENGINE = MergeTree() ORDER BY x",
            "INSERT INTO t VALUES (1), (2)", "CREATE TABLE u(x Int32) ENGINE = MergeTree() ORDER BY x",
            "INSERT INTO u VALUES (3)");

    private static final String ENGINES = "SELECT concat(name, '|', engine) FROM system.tables"
            + " WHERE database = currentDatabase() ORDER BY name";

    @ParameterizedTest(name = "clickhouse:{0}")
    @ValueSource(strings = { "21.3", "21.8", "23.8", "24.8", "25.8", "26.6" })
    void create_or_replace_view(String tag) throws Exception {
        try (ClickHouseContainer ch = ClickHouseContainers.container(tag)) {
            ch.start();
            try (Connection c = DriverManager.getConnection(ClickHouseContainers.jdbcUrl(ch, tag), ch.getUsername(),
                    ch.getPassword()); Statement st = c.createStatement()) {
                for (String sql : SETUP) {
                    st.execute(sql);
                }
                ClickHouseDialect dialect = new ClickHouseDialect(DialectInitData.fromConnection(c));
                assertThat(dialect.supportsCreateOrReplaceView()).as("supportsCreateOrReplaceView").isFalse();

                String create = dialect.createView(V, "SELECT x FROM t", true);
                assertThat(create).as("a plain CREATE VIEW").startsWith("CREATE VIEW ").doesNotContain("REPLACE");
                execute(st, create);
                assertThat(rows(st, "SELECT toString(x) FROM v ORDER BY x")).as("created where there is none")
                        .containsExactly("1", "2");
                assertThatThrownBy(() -> st.execute(dialect.createView(V, "SELECT x * 10 AS x FROM t", true)))
                        .as("a view of the name").isInstanceOf(SQLException.class);

                TableReference table = new TableReference(Optional.empty(), "t", TableReference.TYPE_VIEW);
                assertThatThrownBy(() -> st.execute(dialect.createView(table, "SELECT 1 AS x", true)))
                        .as("a table of the name").isInstanceOf(SQLException.class);
                assertThat(rows(st, "SELECT toString(x) FROM t ORDER BY x")).as("the table keeps its rows")
                        .containsExactly("1", "2");

                // why: the server's own OR REPLACE replaces a view, and a table as well, its rows gone
                st.execute("CREATE OR REPLACE VIEW v AS SELECT x * 10 AS x FROM t");
                assertThat(rows(st, "SELECT toString(x) FROM v ORDER BY x")).as("OR REPLACE replaces a view")
                        .containsExactly("10", "20");
                st.execute("CREATE OR REPLACE VIEW u AS SELECT 1 AS x");
                assertThat(rows(st, ENGINES)).as("and a table, dropped for a view")
                        .containsExactly("t|MergeTree", "u|View", "v|View");
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
