/*
 * Copyright (c) 2026 Contributors to the Eclipse Foundation.
 *
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.daanse.sql.dialect.db.oracle.container;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.JDBCType;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

import org.eclipse.daanse.sql.dialect.api.DialectInitData;
import org.eclipse.daanse.sql.dialect.api.generator.DdlGenerator;
import org.eclipse.daanse.sql.dialect.db.oracle.OracleDialect;
import org.eclipse.daanse.sql.jdbc.record.schema.ColumnDefinitionRecord;
import org.eclipse.daanse.sql.jdbc.record.schema.ColumnMetaDataRecord;
import org.eclipse.daanse.sql.jdbc.record.schema.PrimaryKeyRecord;
import org.eclipse.daanse.sql.model.schema.ColumnDefinition;
import org.eclipse.daanse.sql.model.schema.ColumnMetaData;
import org.eclipse.daanse.sql.model.schema.ColumnReference;
import org.eclipse.daanse.sql.model.schema.SchemaReference;
import org.eclipse.daanse.sql.model.schema.TableReference;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.testcontainers.containers.JdbcDatabaseContainer;

/**
 * Oracle writes {@code IF [NOT] EXISTS} for the DDL of a table, an index and a view from 23 (23ai) on:
 * {@code CREATE TABLE IF NOT EXISTS}, {@code CREATE INDEX IF NOT EXISTS}, {@code DROP INDEX IF EXISTS},
 * {@code DROP VIEW IF EXISTS}; and for a constraint in no version, {@code ALTER TABLE … DROP CONSTRAINT IF EXISTS}
 * failing with ORA-01735 on 23 too. The dialect is built from a live connection: from 23 on it writes the clause and
 * a second create or drop passes, leaving what is there; before, on 18 and 21, it leaves it out, for the server refuses
 * it, and a second create or drop fails. {@code DROP TABLE IF EXISTS} is {@link OracleDropTableIfExistsContainerTest}'s.
 */
@EnabledIfSystemProperty(named = "integration.docker", matches = "true")
class OracleIfExistsTest {

    @ParameterizedTest(name = "{0} -> IF [NOT] EXISTS {1}")
    @CsvSource({ "gvenzl/oracle-xe:18-slim-faststart, false", "gvenzl/oracle-xe:21-slim-faststart, false",
            "gvenzl/oracle-free:23.9-slim-faststart, true",
            "gvenzl/oracle-free:23.26.4-slim-faststart, true" })
    void ifExists(String image, boolean expected) throws Exception {
        try (JdbcDatabaseContainer<?> ora = OracleContainers.of(image)) {
            ora.start();
            try (Connection c = DriverManager.getConnection(ora.getJdbcUrl(), ora.getUsername(), ora.getPassword());
                    Statement st = c.createStatement()) {
                String schema = ora.getUsername().toUpperCase();
                TableReference items = new TableReference(Optional.of(new SchemaReference(Optional.empty(), schema)),
                        "ITEMS", TableReference.TYPE_TABLE);
                TableReference names = new TableReference(Optional.of(new SchemaReference(Optional.empty(), schema)),
                        "ITEM_NAMES", TableReference.TYPE_VIEW);
                OracleDialect dialect = new OracleDialect(DialectInitData.fromConnection(c));
                DdlGenerator ddl = dialect.ddlGenerator();

                assertThat(dialect.supportsCreateTableIfNotExists()).as("CREATE TABLE IF NOT EXISTS")
                        .isEqualTo(expected);
                assertThat(dialect.supportsCreateIndexIfNotExists()).as("CREATE INDEX IF NOT EXISTS")
                        .isEqualTo(expected);
                assertThat(dialect.supportsDropIndexIfExists()).as("DROP INDEX IF EXISTS").isEqualTo(expected);
                assertThat(dialect.supportsDropViewIfExists()).as("DROP VIEW IF EXISTS").isEqualTo(expected);
                assertThat(dialect.supportsDropConstraintIfExists()).as("DROP CONSTRAINT IF EXISTS, in no version")
                        .isFalse();

                // CREATE TABLE
                List<ColumnDefinition> columns = List.of(column(items, "ID"), column(items, "NAME"));
                String createTable = ddl.createTable(items, columns,
                        new PrimaryKeyRecord(items, List.of(columns.get(0).column()), Optional.of("PK_ITEMS")), true);
                assertThat(createTable.startsWith("CREATE TABLE IF NOT EXISTS ")).as(createTable).isEqualTo(expected);
                st.execute(createTable);
                st.execute("INSERT INTO ITEMS VALUES (1, 'apple')");
                assertThat(count(st, "SELECT COUNT(*) FROM USER_TABLES WHERE TABLE_NAME = 'ITEMS'")).isEqualTo(1);
                createdAgain(st, createTable, expected, "ORA-00955");
                assertThat(count(st, "SELECT COUNT(*) FROM ITEMS")).as("the table and its row left").isEqualTo(1);

                // CREATE INDEX
                String createIndex = ddl.createIndex("IX_ITEMS_NAME", items, List.of("NAME"), false, true);
                assertThat(createIndex.startsWith("CREATE INDEX IF NOT EXISTS ")).as(createIndex).isEqualTo(expected);
                st.execute(createIndex);
                assertThat(index(st)).as("the index").isTrue();
                createdAgain(st, createIndex, expected, "ORA-00955");

                // DROP INDEX
                String dropIndex = ddl.dropIndex("IX_ITEMS_NAME", items, true);
                assertThat(dropIndex).isEqualTo(
                        "DROP INDEX " + (expected ? "IF EXISTS " : "") + "\"" + schema + "\".\"IX_ITEMS_NAME\"");
                st.execute(dropIndex);
                assertThat(index(st)).as("the index dropped").isFalse();
                createdAgain(st, dropIndex, expected, "ORA-01418");

                // DROP VIEW
                st.execute(ddl.createView(names, "SELECT NAME FROM ITEMS", false));
                String dropView = ddl.dropView(names, true);
                assertThat(dropView).isEqualTo(
                        "DROP VIEW " + (expected ? "IF EXISTS " : "") + "\"" + schema + "\".\"ITEM_NAMES\"");
                st.execute(dropView);
                assertThat(count(st, "SELECT COUNT(*) FROM USER_VIEWS WHERE VIEW_NAME = 'ITEM_NAMES'"))
                        .as("the view dropped").isZero();
                createdAgain(st, dropView, expected, "ORA-00942");

                // DROP CONSTRAINT: no IF EXISTS in any version
                String dropConstraint = ddl.dropConstraint(items, "PK_ITEMS", true);
                assertThat(dropConstraint).isEqualTo(
                        "ALTER TABLE \"" + schema + "\".\"ITEMS\" DROP CONSTRAINT \"PK_ITEMS\"");
                assertThatThrownBy(() -> st.execute("ALTER TABLE ITEMS DROP CONSTRAINT IF EXISTS PK_ITEMS"))
                        .as("IF EXISTS is refused for a constraint").isInstanceOf(SQLException.class)
                        .hasMessageContaining("ORA-01735");
                st.execute(dropConstraint);
                assertThatThrownBy(() -> st.execute(dropConstraint)).as("a missing one")
                        .isInstanceOf(SQLException.class).hasMessageContaining("ORA-02443");
            }
        }
    }

    /**
     * The statement a second time: with IF [NOT] EXISTS it passes, without it fails with the error of an existing or a
     * missing object.
     */
    private static void createdAgain(Statement st, String sql, boolean ifExists, String error) throws SQLException {
        if (ifExists) {
            st.execute(sql);
        } else {
            assertThatThrownBy(() -> st.execute(sql)).as("a second time: " + sql).isInstanceOf(SQLException.class)
                    .hasMessageContaining(error);
        }
    }

    private static ColumnDefinition column(TableReference table, String name) {
        boolean id = "ID".equals(name);
        JDBCType type = id ? JDBCType.INTEGER : JDBCType.VARCHAR;
        return new ColumnDefinitionRecord(new ColumnReference(Optional.of(table), name),
                new ColumnMetaDataRecord(type, type.getName(), id ? OptionalInt.empty() : OptionalInt.of(20),
                        OptionalInt.empty(), OptionalInt.empty(),
                        id ? ColumnMetaData.Nullability.NO_NULLS : ColumnMetaData.Nullability.NULLABLE,
                        OptionalInt.empty(), Optional.empty(), Optional.empty(), ColumnMetaData.AutoIncrement.UNKNOWN,
                        ColumnMetaData.GeneratedColumn.UNKNOWN));
    }

    private static boolean index(Statement st) throws SQLException {
        return count(st, "SELECT COUNT(*) FROM USER_INDEXES WHERE INDEX_NAME = 'IX_ITEMS_NAME'") > 0;
    }

    private static int count(Statement st, String sql) throws SQLException {
        try (ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }
}
