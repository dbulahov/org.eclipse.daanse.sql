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
 * ClickHouse renames a column with {@code ALTER TABLE … RENAME COLUMN} from 20.4, which changes the name only. The
 * dialect is built from a live connection, and the column it renames must keep its type, its nullability, its
 * default, its comment and its values. A column of the sorting key cannot be renamed on any version. The images of
 * {@code clickhouse/clickhouse-server} begin with 21.3, so the boundary itself is not run here.
 */
@EnabledIfSystemProperty(named = "integration.docker", matches = "true")
class ClickHouseRenameColumnTest {

    private static final TableReference T = new TableReference(Optional.empty(), "t", TableReference.TYPE_TABLE);

    private static final List<String> SETUP = List.of("""
            CREATE TABLE t(id Int32, note String DEFAULT 'n/a' COMMENT 'the note', price Nullable(Decimal(10, 2)))
            ENGINE = MergeTree() ORDER BY id""", "INSERT INTO t(id, note, price) VALUES (1, 'x', 1.50)",
            "INSERT INTO t(id, price) VALUES (2, NULL)");

    /** Name, type, default and comment of each column, in the order of the table. */
    private static final String COLUMNS = """
            SELECT concat(name, '|', type, '|', if(default_expression = '', '-', default_expression), '|',
                if(comment = '', '-', comment))
            FROM system.columns WHERE database = currentDatabase() AND table = 't'""";

    @ParameterizedTest(name = "clickhouse:{0}")
    @ValueSource(strings = { "21.3", "21.8", "23.8", "24.8", "25.8", "26.6" })
    void rename_column(String tag) throws Exception {
        try (ClickHouseContainer ch = ClickHouseContainers.container(tag)) {
            ch.start();
            try (Connection c = DriverManager.getConnection(ClickHouseContainers.jdbcUrl(ch, tag), ch.getUsername(),
                    ch.getPassword()); Statement db = c.createStatement()) {
                for (String sql : SETUP) {
                    db.execute(sql);
                }
                ClickHouseDialect dialect = new ClickHouseDialect(DialectInitData.fromConnection(c));
                assertThat(dialect.supportsRenameColumn()).isTrue();

                rename(db, dialect, "note", "remark");
                rename(db, dialect, "price", "cost");

                assertThat(rows(db, COLUMNS)).as("each column renamed, and kept whole").containsExactly(
                        "id|Int32|-|-", "remark|String|'n/a'|the note", "cost|Nullable(Decimal(10, 2))|-|-");
                assertThat(rows(db, "SELECT concat(toString(id), '|', remark, '|', ifNull(toString(toFloat64(cost)),"
                        + " '-')) FROM t ORDER BY id")).as("the values kept").containsExactly("1|x|1.5", "2|n/a|-");
                db.execute("INSERT INTO t(id) VALUES (3)");
                assertThat(rows(db, "SELECT remark FROM t WHERE id = 3")).as("the default kept")
                        .containsExactly("n/a");

                String key = dialect.renameColumn(T, "id", "key_id");
                assertThatThrownBy(() -> db.execute(key)).as("a column of the sorting key")
                        .isInstanceOf(SQLException.class);
            }
        }
    }

    /** The dialect's rename, run. */
    private static void rename(Statement db, ClickHouseDialect dialect, String from, String to) throws SQLException {
        String sql = dialect.renameColumn(T, from, to);
        assertThat(sql).contains("RENAME COLUMN");
        try {
            db.execute(sql);
        } catch (SQLException e) {
            throw new SQLException(e.getMessage() + " in " + sql, e);
        }
    }

    private static List<String> rows(Statement db, String sql) throws SQLException {
        List<String> out = new ArrayList<>();
        try (ResultSet rs = db.executeQuery(sql)) {
            while (rs.next()) {
                out.add(rs.getString(1));
            }
        }
        return out;
    }
}
