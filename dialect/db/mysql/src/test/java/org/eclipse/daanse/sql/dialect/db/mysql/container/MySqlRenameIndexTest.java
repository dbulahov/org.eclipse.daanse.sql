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
import org.eclipse.daanse.sql.dialect.db.mysql.MySqlDialect;
import org.eclipse.daanse.sql.model.schema.SchemaReference;
import org.eclipse.daanse.sql.model.schema.TableReference;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.testcontainers.containers.MySQLContainer;

/**
 * MySQL renames an index with {@code ALTER TABLE t RENAME INDEX old TO new} since 5.7. The dialect is built from a
 * live connection: from 5.7 on the index it renames keeps its columns and its uniqueness under the new name; on 5.6 it
 * writes no rename ({@code null}, as {@code supportsRenameIndex()} says), for the server would refuse it.
 */
@EnabledIfSystemProperty(named = "integration.docker", matches = "true")
class MySqlRenameIndexTest {

    private static final TableReference USERS = new TableReference(
            Optional.of(new SchemaReference(Optional.empty(), "rt")), "users", TableReference.TYPE_TABLE);

    private static final List<String> SETUP = List.of(
            "CREATE TABLE users(id INT PRIMARY KEY, name VARCHAR(20), email VARCHAR(40))",
            "CREATE INDEX idx_name ON users(name)", "CREATE UNIQUE INDEX uq_email ON users(email)");

    /** Name, column and uniqueness of the table's indexes but the primary key, by name. */
    private static final String INDEXES = """
            SELECT CONCAT(INDEX_NAME, ':', COLUMN_NAME, ':', IF(NON_UNIQUE = 0, 'unique', 'plain'))
            FROM information_schema.STATISTICS
            WHERE TABLE_SCHEMA = 'rt' AND TABLE_NAME = 'users' AND INDEX_NAME <> 'PRIMARY'
            ORDER BY INDEX_NAME""";

    @ParameterizedTest(name = "mysql:{0} -> RENAME INDEX {1}")
    @CsvSource({ "5.6, false", "5.7, true", "8.0, true", "8.4, true", "9, true" })
    void rename_index(String tag, boolean expected) throws Exception {
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

                assertThat(dialect.supportsRenameIndex()).as("supportsRenameIndex").isEqualTo(expected);

                if (!expected) {
                    assertThat(dialect.renameIndex("idx_name", "idx_user_name", USERS)).as("no rename on 5.6")
                            .isNull();
                    assertThatThrownBy(() -> st.execute("ALTER TABLE users RENAME INDEX idx_name TO idx_user_name"))
                            .as("MySQL 5.6 knows no RENAME INDEX").isInstanceOf(SQLException.class)
                            .hasMessageContaining("syntax");
                    return;
                }

                st.execute(dialect.renameIndex("idx_name", "idx_user_name", USERS));
                st.execute(dialect.renameIndex("uq_email", "uq_user_email", USERS));
                assertThat(rows(st, INDEXES)).as("the new names, the columns and the uniqueness kept")
                        .containsExactly("idx_user_name:name:plain", "uq_user_email:email:unique");

                st.execute("INSERT INTO users VALUES (1, 'a', 'a@x')");
                assertThatThrownBy(() -> st.execute("INSERT INTO users VALUES (2, 'b', 'a@x')"))
                        .as("the renamed unique index still unique").isInstanceOf(SQLException.class)
                        .hasMessageContaining("uq_user_email");

                assertThatThrownBy(() -> st.execute(dialect.renameIndex("idx_name", "idx_again", USERS)))
                        .as("the old name is gone").isInstanceOf(SQLException.class);

                assertThatThrownBy(() -> dialect.renameIndex("idx_user_name", "x", null))
                        .as("an index of no table").isInstanceOf(IllegalArgumentException.class);
            }
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
