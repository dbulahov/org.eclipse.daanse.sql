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
 * ClickHouse sets the comment of a column with {@code ALTER TABLE t COMMENT COLUMN c '…'}, which changes the comment
 * only, on every version here; the column of a view from 22.1 (21.11 and 21.12 write a view's comments into metadata
 * they cannot read again, see {@link ClickHouseCommentOnTableTest}). The dialect is built from a live connection: the
 * comment it writes, with a quote and a backslash in it, is the column's, on a column of the sorting key and a
 * Nullable one too, stays when the table is detached and attached again, and a {@code null} clears it; the column
 * keeps its type, its default and its values. From 22.1 on so for a view's column; before, it writes none there.
 */
@EnabledIfSystemProperty(named = "integration.docker", matches = "true")
class ClickHouseCommentOnColumnTest {

    private static final TableReference T = new TableReference(Optional.empty(), "t", TableReference.TYPE_TABLE);
    private static final TableReference V = new TableReference(Optional.empty(), "v", TableReference.TYPE_VIEW);

    private static final List<String> SETUP = List.of("""
            CREATE TABLE t(id Int32, note String DEFAULT 'n/a', price Nullable(Decimal(10, 2)))
            ENGINE = MergeTree() ORDER BY id""", "INSERT INTO t(id, note, price) VALUES (1, 'x', 1.5), (2, 'y', NULL)",
            "CREATE VIEW v AS SELECT id, note FROM t");

    private static final String COMMENT = "it's a \\ comment";

    /** Name, type, default and comment of each column of the table, in its order. */
    private static final String COLUMNS = """
            SELECT concat(name, '|', type, '|', if(default_expression = '', '-', default_expression), '|',
                if(comment = '', '-', comment))
            FROM system.columns WHERE database = currentDatabase() AND table = '%s'""";

    @ParameterizedTest(name = "clickhouse:{0} -> view column {1}")
    @CsvSource({ "21.3, false", "21.8, false", "21.12, false", "22.1, true", "23.8, true", "24.8, true",
            "25.8, true", "26.6, true" })
    void comment_on_column(String tag, boolean onView) throws Exception {
        try (ClickHouseContainer ch = ClickHouseContainers.container(tag)) {
            ch.start();
            try (Connection c = DriverManager.getConnection(ClickHouseContainers.jdbcUrl(ch, tag), ch.getUsername(),
                    ch.getPassword()); Statement st = c.createStatement()) {
                for (String sql : SETUP) {
                    st.execute(sql);
                }
                ClickHouseDialect dialect = new ClickHouseDialect(DialectInitData.fromConnection(c));

                execute(st, dialect.commentOnColumn(T, "note", COMMENT, null).orElseThrow());
                execute(st, dialect.commentOnColumn(T, "id", "the key", null).orElseThrow());
                execute(st, dialect.commentOnColumn(T, "price", "may be null", null).orElseThrow());
                assertThat(rows(st, COLUMNS.formatted("t"))).as("each comment, the column kept whole")
                        .containsExactly("id|Int32|-|the key", "note|String|'n/a'|" + COMMENT,
                                "price|Nullable(Decimal(10, 2))|-|may be null");

                st.execute("DETACH TABLE t");
                st.execute("ATTACH TABLE t");
                assertThat(rows(st, COLUMNS.formatted("t"))).as("kept when the table is attached again")
                        .contains("note|String|'n/a'|" + COMMENT);

                execute(st, dialect.commentOnColumn(T, "note", "another", null).orElseThrow());
                execute(st, dialect.commentOnColumn(T, "id", null, null).orElseThrow());
                assertThat(rows(st, COLUMNS.formatted("t"))).as("replaced, and null clears it")
                        .containsExactly("id|Int32|-|-", "note|String|'n/a'|another",
                                "price|Nullable(Decimal(10, 2))|-|may be null");
                assertThat(rows(st, "SELECT concat(toString(id), '|', note, '|', ifNull(toString(toFloat64(price)),"
                        + " '-')) FROM t ORDER BY id")).as("the values kept").containsExactly("1|x|1.5", "2|y|-");
                st.execute("INSERT INTO t(id) VALUES (3)");
                assertThat(rows(st, "SELECT note FROM t WHERE id = 3")).as("the default kept").containsExactly("n/a");

                if (!onView) {
                    assertThat(dialect.commentOnColumn(V, "note", COMMENT, null)).as("no comment on a view's column"
                            + " before 22.1").isEmpty();
                    return;
                }
                execute(st, dialect.commentOnColumn(V, "note", COMMENT, null).orElseThrow());
                assertThat(rows(st, COLUMNS.formatted("v"))).as("a view's column's comment")
                        .containsExactly("id|Int32|-|-", "note|String|-|" + COMMENT);
                st.execute("DETACH TABLE v");
                st.execute("ATTACH TABLE v");
                assertThat(rows(st, COLUMNS.formatted("v"))).as("read again when the view is attached")
                        .containsExactly("id|Int32|-|-", "note|String|-|" + COMMENT);
                assertThat(rows(st, "SELECT note FROM v ORDER BY id")).as("the view still reads")
                        .containsExactly("x", "y", "n/a");
                execute(st, dialect.commentOnColumn(V, "note", null, null).orElseThrow());
                assertThat(rows(st, COLUMNS.formatted("v"))).as("null clears a view's column's")
                        .containsExactly("id|Int32|-|-", "note|String|-|-");
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
