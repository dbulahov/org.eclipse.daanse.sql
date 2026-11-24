/*
 * Copyright (c) 2026 Contributors to the Eclipse Foundation.
 *
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.daanse.sql.dialect.db.mssqlserver.container;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;

import org.eclipse.daanse.sql.dialect.api.DialectInitData;
import org.eclipse.daanse.sql.dialect.api.generator.DdlGenerator;
import org.eclipse.daanse.sql.dialect.db.mssqlserver.MicrosoftSqlServerDialect;
import org.eclipse.daanse.sql.model.schema.SchemaReference;
import org.eclipse.daanse.sql.model.schema.TableReference;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.containers.MSSQLServerContainer;

/**
 * SQL Server drops a table, a view, an index and a schema only if it exists from 2016 on, so in every version Docker
 * serves, 2017 to 2025. The dialect is built from a live connection: each drop it writes with {@code ifExists} drops
 * what is there and passes on what is not, where the drop without it fails; a table whose name holds a quote, a dot or
 * a blank is dropped too, and a view of the table's name is no table and is left. IF EXISTS spares no other error: a
 * schema that holds a table is not dropped.
 */
@EnabledIfSystemProperty(named = "integration.docker", matches = "true")
class MsSqlDropIfExistsTest {

    private static final SchemaReference DBO = new SchemaReference(Optional.empty(), "dbo");

    @ParameterizedTest(name = "mssql/server:{0}")
    @ValueSource(strings = { "2017-latest", "2019-latest", "2022-latest", "2025-latest" })
    void dropIfExists(String tag) throws Exception {
        try (MSSQLServerContainer<?> ms = new MSSQLServerContainer<>("mcr.microsoft.com/mssql/server:" + tag)
                .acceptLicense()) {
            ms.start();
            try (Connection c = DriverManager.getConnection(ms.getJdbcUrl(), ms.getUsername(), ms.getPassword());
                    Statement st = c.createStatement()) {
                MicrosoftSqlServerDialect dialect = new MicrosoftSqlServerDialect(DialectInitData.fromConnection(c));
                DdlGenerator ddl = dialect.ddlGenerator();

                assertThat(dialect.supportsDropTableIfExists()).as("supportsDropTableIfExists").isTrue();
                assertThat(dialect.supportsDropViewIfExists()).as("supportsDropViewIfExists").isTrue();
                assertThat(dialect.supportsDropIndexIfExists()).as("supportsDropIndexIfExists").isTrue();
                assertThat(dialect.supportsDropSchemaIfExists()).as("supportsDropSchemaIfExists").isTrue();

                // DROP TABLE, of plain names and of names holding a quote, a dot, a blank
                for (String name : List.of("items", "it's", "a.b", "order details")) {
                    String quoted = dialect.quoteIdentifier("dbo", name);
                    String drop = dialect.dropTable("dbo", name, true);
                    st.execute("CREATE TABLE " + quoted + " (id INT)");
                    assertThat(exists(st, name, "U")).as("created: " + name).isTrue();
                    st.execute(drop);
                    assertThat(exists(st, name, "U")).as("dropped: " + drop).isFalse();
                    st.execute(drop);
                    assertThatThrownBy(() -> st.execute(dialect.dropTable("dbo", name, false)))
                            .as("a missing one without IF EXISTS: " + name).isInstanceOf(SQLException.class);
                }
                st.execute("CREATE TABLE dbo.base (id INT)");
                st.execute("CREATE VIEW dbo.notatable AS SELECT id FROM dbo.base");
                st.execute(dialect.dropTable("dbo", "notatable", true));
                assertThat(exists(st, "notatable", "V")).as("a view of the name is no table, and left").isTrue();

                // DROP VIEW
                TableReference view = new TableReference(Optional.of(DBO), "notatable", TableReference.TYPE_VIEW);
                String dropView = ddl.dropView(view, true);
                assertThat(dropView).isEqualTo("DROP VIEW IF EXISTS \"dbo\".\"notatable\"");
                st.execute(dropView);
                assertThat(exists(st, "notatable", "V")).as("the view dropped").isFalse();
                st.execute(dropView);
                assertThatThrownBy(() -> st.execute(ddl.dropView(view, false))).as("a missing view without IF EXISTS")
                        .isInstanceOf(SQLException.class);

                // DROP INDEX … ON table
                TableReference base = new TableReference(Optional.of(DBO), "base", TableReference.TYPE_TABLE);
                st.execute("CREATE INDEX ix_id ON dbo.base (id)");
                String dropIndex = ddl.dropIndex("ix_id", base, true);
                assertThat(dropIndex).isEqualTo("DROP INDEX IF EXISTS \"ix_id\" ON \"dbo\".\"base\"");
                st.execute(dropIndex);
                assertThat(count(st, "SELECT COUNT(*) FROM sys.indexes WHERE name = 'ix_id'")).as("the index dropped")
                        .isZero();
                st.execute(dropIndex);
                assertThatThrownBy(() -> st.execute(ddl.dropIndex("ix_id", base, false)))
                        .as("a missing index without IF EXISTS").isInstanceOf(SQLException.class);

                // DROP SCHEMA: an empty one dropped; one that holds a table not
                st.execute(dialect.createSchema("rt", true));
                String dropSchema = dialect.dropSchema("rt", true, false);
                assertThat(dropSchema).isEqualTo("DROP SCHEMA IF EXISTS \"rt\"");
                st.execute("CREATE TABLE rt.t (id INT)");
                assertThatThrownBy(() -> st.execute(dropSchema)).as("a schema that holds a table")
                        .isInstanceOf(SQLException.class).hasMessageContaining("referenced by object");
                st.execute("DROP TABLE rt.t");
                st.execute(dropSchema);
                assertThat(count(st, "SELECT COUNT(*) FROM sys.schemas WHERE name = 'rt'")).as("the schema dropped")
                        .isZero();
                st.execute(dropSchema);
                assertThatThrownBy(() -> st.execute(dialect.dropSchema("rt", false, false)))
                        .as("a missing schema without IF EXISTS").isInstanceOf(SQLException.class);
            }
        }
    }

    private static boolean exists(Statement st, String name, String type) throws SQLException {
        return count(st, "SELECT COUNT(*) FROM sys.objects WHERE schema_id = SCHEMA_ID('dbo') AND name = N'"
                + name.replace("'", "''") + "' AND type = '" + type + "'") > 0;
    }

    private static int count(Statement st, String sql) throws SQLException {
        try (ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }
}
