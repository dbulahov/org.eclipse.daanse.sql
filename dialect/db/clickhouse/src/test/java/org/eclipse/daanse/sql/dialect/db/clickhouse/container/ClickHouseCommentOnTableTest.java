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
 * ClickHouse sets the comment of a table with {@code ALTER TABLE t MODIFY COMMENT '…'} from 21.11; 21.10 refuses it
 * with a syntax error. It takes a view there as a table, and the column of a view in {@code COMMENT COLUMN}, but 21.11
 * and 21.12 write the comment of a view into metadata they cannot read again: the next ALTER of the view fails, and
 * the server does not start again; 22.1 reads it. The dialect is built from a live connection: from 21.11 on the
 * comment it writes, with a quote and a backslash in it, is the table's, stays with it when it is detached and
 * attached again, and a {@code null} clears it; from 22.1 on so for a view and a view's column, which then still
 * takes an ALTER and is read again when attached. Before, it writes no comment there.
 */
@EnabledIfSystemProperty(named = "integration.docker", matches = "true")
class ClickHouseCommentOnTableTest {

    private static final TableReference T = new TableReference(Optional.empty(), "t", TableReference.TYPE_TABLE);
    private static final TableReference V = new TableReference(Optional.empty(), "v", TableReference.TYPE_VIEW);

    private static final List<String> SETUP = List.of(
            "CREATE TABLE t(id Int32) ENGINE = MergeTree() ORDER BY id",
            "CREATE VIEW v AS SELECT id FROM t");

    private static final String COMMENT = "it's a \\ comment";

    @ParameterizedTest(name = "clickhouse:{0} -> table {1}, view {2}")
    @CsvSource({ "21.8, false, false", "21.10, false, false", "21.11, true, false", "21.12, true, false",
            "22.1, true, true", "23.8, true, true", "24.8, true, true", "25.8, true, true", "26.6, true, true" })
    void comment_on_table(String tag, boolean onTable, boolean onView) throws Exception {
        try (ClickHouseContainer ch = ClickHouseContainers.container(tag)) {
            ch.start();
            try (Connection c = DriverManager.getConnection(ClickHouseContainers.jdbcUrl(ch, tag), ch.getUsername(),
                    ch.getPassword()); Statement st = c.createStatement()) {
                for (String sql : SETUP) {
                    st.execute(sql);
                }
                ClickHouseDialect dialect = new ClickHouseDialect(DialectInitData.fromConnection(c));
                assertThat(dialect.supportsCommentOnView()).as("supportsCommentOnView").isEqualTo(onView);

                if (!onView) {
                    assertThat(dialect.commentOnTable(V, COMMENT)).as("no comment on a view before 22.1").isEmpty();
                    assertThat(dialect.commentOnColumn(V, "id", COMMENT, null)).as("nor on a view's column")
                            .isEmpty();
                }
                if (!onTable) {
                    assertThat(dialect.commentOnTable(T, COMMENT)).as("no comment before 21.11").isEmpty();
                    assertThatThrownBy(() -> st.execute("ALTER TABLE t MODIFY COMMENT 'x'"))
                            .as("the server refuses it").isInstanceOf(SQLException.class)
                            .hasMessageContaining("Syntax error");
                    return;
                }

                execute(st, dialect.commentOnTable(T, COMMENT).orElseThrow());
                assertThat(comment(st, "t")).as("the comment, quote and backslash kept").isEqualTo(COMMENT);
                st.execute("DETACH TABLE t");
                st.execute("ATTACH TABLE t");
                assertThat(comment(st, "t")).as("kept when the table is attached again").isEqualTo(COMMENT);
                execute(st, dialect.commentOnTable(T, "another").orElseThrow());
                assertThat(comment(st, "t")).as("replaced").isEqualTo("another");
                execute(st, dialect.commentOnTable(T, null).orElseThrow());
                assertThat(comment(st, "t")).as("null clears it").isEmpty();
                if (!onView) {
                    return;
                }

                execute(st, dialect.commentOnTable(V, COMMENT).orElseThrow());
                assertThat(comment(st, "v")).as("a view's comment").isEqualTo(COMMENT);
                execute(st, dialect.commentOnColumn(V, "id", COMMENT, null).orElseThrow());
                assertThat(rows(st, "SELECT comment FROM system.columns WHERE database = currentDatabase()"
                        + " AND table = 'v' AND name = 'id'")).as("a view's column's comment").containsExactly(COMMENT);
                st.execute("DETACH TABLE v");
                st.execute("ATTACH TABLE v");
                assertThat(comment(st, "v")).as("a view's, read again when attached").isEqualTo(COMMENT);
                execute(st, dialect.commentOnTable(V, null).orElseThrow());
                assertThat(comment(st, "v")).as("null clears a view's").isEmpty();
            }
        }
    }

    private static String comment(Statement st, String table) throws SQLException {
        List<String> rows = rows(st,
                "SELECT comment FROM system.tables WHERE database = currentDatabase() AND name = '" + table + "'");
        assertThat(rows).hasSize(1);
        return rows.get(0);
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
