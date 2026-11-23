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
import java.sql.JDBCType;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

import org.eclipse.daanse.sql.dialect.api.DialectInitData;
import org.eclipse.daanse.sql.dialect.db.mysql.MySqlDialect;
import org.eclipse.daanse.sql.jdbc.record.schema.ColumnMetaDataRecord;
import org.eclipse.daanse.sql.model.schema.ColumnMetaData;
import org.eclipse.daanse.sql.model.schema.ColumnMetaData.AutoIncrement;
import org.eclipse.daanse.sql.model.schema.ColumnMetaData.GeneratedColumn;
import org.eclipse.daanse.sql.model.schema.ColumnMetaData.Nullability;
import org.eclipse.daanse.sql.model.schema.SchemaReference;
import org.eclipse.daanse.sql.model.schema.TableReference;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.testcontainers.containers.MySQLContainer;

/**
 * MySQL renames a column with {@code RENAME COLUMN} from 8.0, which changes the name only. Before, the dialect writes
 * {@code CHANGE old new <definition>}, which replaces the whole column with the definition it is given, from the
 * column's metadata. The dialect is built from a live connection, and the column it renames must keep, on every
 * version, its type, its nullability, its default, its comment, its AUTO_INCREMENT and its values; without the
 * metadata it renames from 8.0 on, and writes no rename before.
 */
@EnabledIfSystemProperty(named = "integration.docker", matches = "true")
class MySqlRenameColumnTest {

    private static final TableReference T = new TableReference(
            Optional.of(new SchemaReference(Optional.empty(), "rt")), "t", TableReference.TYPE_TABLE);

    private static final List<String> SETUP = List.of("""
            CREATE TABLE t(id INT NOT NULL AUTO_INCREMENT PRIMARY KEY,
                note VARCHAR(20) NOT NULL DEFAULT 'n/a' COMMENT 'the note',
                price DECIMAL(10,2) NULL)""", "INSERT INTO t(note, price) VALUES ('x', 1.50), (DEFAULT, NULL)");

    private static final ColumnMetaData ID = meta(JDBCType.INTEGER, OptionalInt.empty(), OptionalInt.empty(),
            Nullability.NO_NULLS, null, null, AutoIncrement.YES);
    private static final ColumnMetaData NOTE = meta(JDBCType.VARCHAR, OptionalInt.of(20), OptionalInt.empty(),
            Nullability.NO_NULLS, "'n/a'", "the note", AutoIncrement.NO);
    private static final ColumnMetaData PRICE = meta(JDBCType.DECIMAL, OptionalInt.of(10), OptionalInt.of(2),
            Nullability.NULLABLE, null, null, AutoIncrement.NO);

    /** Type, nullability, default, extra and comment of each column, in order. */
    private static final String COLUMNS = """
            SELECT CONCAT_WS('|', COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, IFNULL(COLUMN_DEFAULT, '-'),
                IF(EXTRA = '', '-', EXTRA), IF(COLUMN_COMMENT = '', '-', COLUMN_COMMENT))
            FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = 'rt' AND TABLE_NAME = 't' ORDER BY ORDINAL_POSITION""";

    @ParameterizedTest(name = "mysql:{0} -> RENAME COLUMN {1}")
    @CsvSource({ "5.7, false", "8.0, true", "8.4, true", "9, true" })
    void rename_column(String tag, boolean renameColumn) throws Exception {
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
                assertThat(dialect.supportsRenameColumn()).isTrue();

                String withoutMeta = dialect.renameColumn(T, "price", "cost");
                if (renameColumn) {
                    assertThat(withoutMeta).contains("RENAME COLUMN");
                    st.execute(withoutMeta);
                    st.execute(dialect.renameColumn(T, "cost", "price"));
                } else {
                    assertThat(withoutMeta).as("no rename without the metadata before 8.0").isNull();
                }

                rename(st, dialect, "note", "remark", NOTE);
                rename(st, dialect, "price", "cost", PRICE);
                rename(st, dialect, "id", "key_id", ID);

                assertThat(rows(st, COLUMNS)).as("each column renamed, and kept whole").containsExactly(
                        "key_id|int(11)|NO|-|auto_increment|-".replace("int(11)", intType(st)),
                        "remark|varchar(20)|NO|n/a|-|the note", "cost|decimal(10,2)|YES|-|-|-");
                assertThat(rows(st, "SELECT CONCAT_WS('|', key_id, remark, IFNULL(cost, '-')) FROM t ORDER BY key_id"))
                        .as("the values kept").containsExactly("1|x|1.50", "2|n/a|-");
                st.execute("INSERT INTO t(remark) VALUES ('y')");
                assertThat(rows(st, "SELECT MAX(key_id) FROM t")).as("AUTO_INCREMENT kept").containsExactly("3");
            }
        }
    }

    /** The dialect's rename with the column's metadata, run. */
    private static void rename(Statement st, MySqlDialect dialect, String from, String to, ColumnMetaData meta)
            throws SQLException {
        String sql = dialect.renameColumn(T, from, to, meta);
        try {
            st.execute(sql);
        } catch (SQLException e) {
            throw new SQLException(e.getMessage() + " in " + sql, e);
        }
    }

    /** MySQL 8.0.19 and later write INT without its display width. */
    private static String intType(Statement st) throws SQLException {
        return rows(st, "SELECT COLUMN_TYPE FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = 'rt'"
                + " AND TABLE_NAME = 't' AND ORDINAL_POSITION = 1").get(0);
    }

    private static ColumnMetaData meta(JDBCType type, OptionalInt size, OptionalInt scale, Nullability nullability,
            String columnDefault, String remarks, AutoIncrement autoIncrement) {
        return new ColumnMetaDataRecord(type, type.getName(), size, scale, OptionalInt.empty(), nullability,
                OptionalInt.empty(), Optional.ofNullable(remarks), Optional.ofNullable(columnDefault), autoIncrement,
                GeneratedColumn.NO);
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
