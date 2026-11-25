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
import org.eclipse.daanse.sql.dialect.api.generator.DdlGenerator.TableRename;
import org.eclipse.daanse.sql.dialect.db.clickhouse.ClickHouseDialect;
import org.eclipse.daanse.sql.model.schema.TableReference;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.clickhouse.ClickHouseContainer;

/**
 * ClickHouse takes {@code RENAME TABLE a TO b, c TO d} as one statement, but runs the renames one after the other and
 * undoes none when a later one fails, in an Atomic database too, the default on every version here: the statement is
 * not atomic, and the dialect says so. Its {@code renameTables} writes one {@code RENAME TABLE} per rename, which,
 * run in order, swap two tables through a third name, their rows going with them, and rename a view as a table. A
 * view reads its table by name, the table there under that name after a swap.
 */
@EnabledIfSystemProperty(named = "integration.docker", matches = "true")
class ClickHouseAtomicMultiRenameTableTest {

    private static final List<String> SETUP = List.of(
            "CREATE TABLE a(x Int32) ENGINE = MergeTree() ORDER BY x",
            "CREATE TABLE b(x Int32) ENGINE = MergeTree() ORDER BY x",
            "CREATE TABLE c(x Int32) ENGINE = MergeTree() ORDER BY x",
            "INSERT INTO a VALUES (1)", "INSERT INTO b VALUES (2)", "INSERT INTO c VALUES (3)",
            "CREATE VIEW v AS SELECT x FROM a");

    private static final String TABLES = "SELECT name FROM system.tables WHERE database = currentDatabase()"
            + " ORDER BY name";

    @ParameterizedTest(name = "clickhouse:{0}")
    @ValueSource(strings = { "21.3", "21.8", "23.8", "24.8", "25.8", "26.6" })
    void multi_rename(String tag) throws Exception {
        try (ClickHouseContainer ch = ClickHouseContainers.container(tag)) {
            ch.start();
            try (Connection c = DriverManager.getConnection(ClickHouseContainers.jdbcUrl(ch, tag), ch.getUsername(),
                    ch.getPassword()); Statement st = c.createStatement()) {
                for (String sql : SETUP) {
                    st.execute(sql);
                }
                assertThat(rows(st, "SELECT engine FROM system.databases WHERE name = currentDatabase()"))
                        .as("an Atomic database").containsExactly("Atomic");
                ClickHouseDialect dialect = new ClickHouseDialect(DialectInitData.fromConnection(c));
                assertThat(dialect.supportsAtomicMultiRenameTable()).as("supportsAtomicMultiRenameTable").isFalse();

                assertThatThrownBy(() -> st.execute("RENAME TABLE a TO a2, b TO c"))
                        .as("the second rename fails, c is there").isInstanceOf(SQLException.class);
                assertThat(rows(st, TABLES)).as("the first one is not undone: not atomic")
                        .containsExactly("a2", "b", "c", "v");
                st.execute("RENAME TABLE a2 TO a");

                List<String> swap = dialect.renameTables(List.of(new TableRename(table("a"), "tmp"),
                        new TableRename(table("b"), "a"), new TableRename(table("tmp"), "b")));
                assertThat(swap).as("one statement per rename").hasSize(3)
                        .allSatisfy(sql -> assertThat(sql).startsWith("RENAME TABLE ").doesNotContain(","));
                for (String sql : swap) {
                    execute(st, sql);
                }
                assertThat(rows(st, TABLES)).as("the same tables").containsExactly("a", "b", "c", "v");
                assertThat(rows(st, "SELECT toString(x) FROM a")).as("a holds b's rows").containsExactly("2");
                assertThat(rows(st, "SELECT toString(x) FROM b")).as("b holds a's rows").containsExactly("1");

                for (String sql : dialect.renameTables(List.of(new TableRename(table("c"), "c2"),
                        new TableRename(new TableReference(Optional.empty(), "v", TableReference.TYPE_VIEW), "v2")))) {
                    execute(st, sql);
                }
                assertThat(rows(st, TABLES)).as("a table and a view renamed").containsExactly("a", "b", "c2", "v2");
                assertThat(rows(st, "SELECT toString(x) FROM v2")).as("the renamed view reads a, by its name")
                        .containsExactly("2");
                assertThat(rows(st, "SELECT toString(x) FROM c2")).as("c2 holds c's rows").containsExactly("3");

                assertThat(dialect.renameTables(List.of())).as("nothing to rename").isEmpty();
            }
        }
    }

    private static TableReference table(String name) {
        return new TableReference(Optional.empty(), name, TableReference.TYPE_TABLE);
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
