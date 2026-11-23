/*
 * Copyright (c) 2026 Contributors to the Eclipse Foundation.
 *
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.daanse.sql.dialect.db.mysql.container;

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
import org.eclipse.daanse.sql.dialect.api.generator.DdlGenerator.IndexColumn;
import org.eclipse.daanse.sql.dialect.api.generator.DdlGenerator.IndexDefinition;
import org.eclipse.daanse.sql.dialect.db.mysql.MySqlDialect;
import org.eclipse.daanse.sql.model.schema.SchemaReference;
import org.eclipse.daanse.sql.model.schema.TableReference;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.testcontainers.containers.MySQLContainer;

/**
 * MySQL keeps a descending column of an index from 8.0; 5.7 takes {@code DESC} and builds the column ascending. The
 * dialect is built from a live connection: from 8.0 on the index it writes keeps each column's order, and an order by
 * the same directions reads the index without a sort; on 5.7 it writes no index with a descending column, for the
 * server would keep it ascending without a word; an ascending index it writes on every version.
 */
@EnabledIfSystemProperty(named = "integration.docker", matches = "true")
class MySqlDescendingIndexTest {

    private static final TableReference USERS = new TableReference(
            Optional.of(new SchemaReference(Optional.empty(), "rt")), "users", TableReference.TYPE_TABLE);

    private static final List<String> SETUP = List.of("CREATE TABLE users(id INT PRIMARY KEY, name VARCHAR(20))",
            "INSERT INTO users VALUES (1, 'a'), (2, 'b'), (3, 'b'), (4, 'c')");

    /** name descending, then id ascending. */
    private static final IndexDefinition MIXED = new IndexDefinition("ix_name_desc", USERS,
            List.of(IndexColumn.descending("name"), IndexColumn.ascending("id")), false, null);
    private static final IndexDefinition ASCENDING = new IndexDefinition("ix_name_asc", USERS,
            List.of(IndexColumn.ascending("name")), false, null);

    /**
     * The order of each column of an index, A or D, as SHOW INDEX gives it: information_schema.STATISTICS has no
     * COLLATION on 5.7, NULL for every column.
     */
    private static List<String> order(Statement st, String index) throws SQLException {
        List<String> out = new ArrayList<>();
        try (ResultSet rs = st.executeQuery("SHOW INDEX FROM users WHERE Key_name = '" + index + "'")) {
            while (rs.next()) {
                out.add(rs.getString("Column_name") + ":" + rs.getString("Collation"));
            }
        }
        return out;
    }

    @ParameterizedTest(name = "mysql:{0} -> descending {1}")
    @CsvSource({ "5.7, false", "8.0, true", "8.4, true", "9, true" })
    void descending_index(String tag, boolean expected) throws Exception {
        // the server's own configuration: Testcontainers' default sets innodb_log_file_size, which MySQL 9 refuses
        try (MySQLContainer<?> my = new MySQLContainer<>("mysql:" + tag).withConfigurationOverride("mysql-conf")
                .withDatabaseName("rt").withUsername("rt").withPassword("rt")) {
            my.start();
            try (Connection c = DriverManager.getConnection(my.getJdbcUrl(), my.getUsername(), my.getPassword());
                    Statement st = c.createStatement()) {
                for (String sql : SETUP) {
                    st.execute(sql);
                }
                MySqlDialect dialect = new MySqlDialect(DialectInitData.fromConnection(c));

                assertThat(dialect.supportsDescendingIndexColumns()).as("supportsDescendingIndexColumns")
                        .isEqualTo(expected);

                st.execute(dialect.createIndex(ASCENDING, false).orElseThrow());
                assertThat(order(st, "ix_name_asc")).as("an ascending index").containsExactly("name:A");

                if (!expected) {
                    assertThat(dialect.createIndex(MIXED, false)).as("no descending column on 5.7").isEmpty();
                    st.execute("CREATE INDEX ix_hand ON users (name DESC, id)");
                    assertThat(order(st, "ix_hand")).as("5.7 takes DESC and keeps the column ascending")
                            .containsExactly("name:A", "id:A");
                    return;
                }

                String sql = dialect.createIndex(MIXED, false).orElseThrow();
                assertThat(sql).contains("DESC");
                st.execute(sql);
                assertThat(order(st, "ix_name_desc")).as("each column's order kept")
                        .containsExactly("name:D", "id:A");

                // EXPLAIN of 9 is a tree unless asked for the table, which has Extra
                String byIndex = "SELECT name, id FROM users FORCE INDEX (ix_name_desc) ORDER BY name DESC, id ASC";
                assertThat(String.join(" ", rows(st, "EXPLAIN FORMAT=TRADITIONAL " + byIndex, "Extra"))).as("read in the index's order")
                        .doesNotContain("filesort");
                assertThat(rows(st, byIndex.replace("SELECT name, id", "SELECT CONCAT(name, id)")))
                        .containsExactly("c4", "b2", "b3", "a1");
            }
        }
    }

    private static List<String> rows(Statement st, String sql) throws SQLException {
        return rows(st, sql, null);
    }

    private static List<String> rows(Statement st, String sql, String column) throws SQLException {
        List<String> out = new ArrayList<>();
        try (ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                out.add(column == null ? rs.getString(1) : rs.getString(column));
            }
        }
        return out;
    }
}
