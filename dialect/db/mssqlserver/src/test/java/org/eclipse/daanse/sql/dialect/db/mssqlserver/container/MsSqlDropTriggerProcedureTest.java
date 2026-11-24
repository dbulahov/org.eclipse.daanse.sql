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
import java.util.Optional;

import org.eclipse.daanse.sql.dialect.api.DialectInitData;
import org.eclipse.daanse.sql.dialect.db.mssqlserver.MicrosoftSqlServerDialect;
import org.eclipse.daanse.sql.model.schema.SchemaReference;
import org.eclipse.daanse.sql.model.schema.TableReference;
import org.eclipse.daanse.sql.model.schema.Trigger.TriggerEvent;
import org.eclipse.daanse.sql.model.schema.Trigger.TriggerScope;
import org.eclipse.daanse.sql.model.schema.Trigger.TriggerTiming;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.containers.MSSQLServerContainer;

/**
 * SQL Server drops a trigger procedure, by schema and name, with {@code DROP PROCEDURE [IF EXISTS] s.p}; IF EXISTS
 * from 2016 on, so in every version Docker serves, 2017 to 2025. The dialect is built from a live connection: the
 * procedure {@code createTriggerProcedure} writes is dropped, of a schema, of none and of a blank one, which name it in
 * the user's default schema, and of a name holding a quote and a blank; a second drop passes with IF EXISTS, fails
 * without it. A procedure a trigger calls is dropped too, for SQL Server resolves the call when the trigger fires:
 * the trigger then fails the statement it fires on.
 */
@EnabledIfSystemProperty(named = "integration.docker", matches = "true")
class MsSqlDropTriggerProcedureTest {

    private static final TableReference ITEMS = new TableReference(
            Optional.of(new SchemaReference(Optional.empty(), "dbo")), "items", TableReference.TYPE_TABLE);

    @ParameterizedTest(name = "mssql/server:{0}")
    @ValueSource(strings = { "2017-latest", "2019-latest", "2022-latest", "2025-latest" })
    void dropTriggerProcedure(String tag) throws Exception {
        try (MSSQLServerContainer<?> ms = new MSSQLServerContainer<>("mcr.microsoft.com/mssql/server:" + tag)
                .acceptLicense()) {
            ms.start();
            try (Connection c = DriverManager.getConnection(ms.getJdbcUrl(), ms.getUsername(), ms.getPassword());
                    Statement st = c.createStatement()) {
                MicrosoftSqlServerDialect dialect = new MicrosoftSqlServerDialect(DialectInitData.fromConnection(c));
                st.execute("CREATE TABLE items(id INT)");
                st.execute("CREATE TABLE audit(op VARCHAR(10))");

                // of a schema, of none, of a blank one; of a name holding a quote and a blank
                assertThat(dialect.dropProcedure("log_it", "dbo", true))
                        .contains("DROP PROCEDURE IF EXISTS \"dbo\".\"log_it\"");
                assertThat(dialect.dropProcedure("log_it", null, true)).contains("DROP PROCEDURE IF EXISTS \"log_it\"");
                assertThat(dialect.dropProcedure("log_it", " ", false)).contains("DROP PROCEDURE \"log_it\"");
                for (String schema : new String[] { "dbo", null, "" }) {
                    for (String name : new String[] { "log_it", "it's a proc" }) {
                        st.execute(dialect.createTriggerProcedure(name, schema,
                                "BEGIN INSERT INTO dbo.audit VALUES ('call') END").orElseThrow());
                        assertThat(exists(st, name)).as("created: " + schema + "." + name).isTrue();
                        String drop = dialect.dropProcedure(name, schema, true).orElseThrow();
                        st.execute(drop);
                        assertThat(exists(st, name)).as("dropped: " + drop).isFalse();
                        st.execute(drop);
                        assertThatThrownBy(() -> st.execute(dialect.dropProcedure(name, schema, false).orElseThrow()))
                                .as("a missing one without IF EXISTS: " + drop).isInstanceOf(SQLException.class)
                                .hasMessageContaining("does not exist");
                    }
                }

                // a procedure a trigger calls: dropped, and the trigger then fails the statement it fires on
                st.execute(dialect.createTriggerProcedure("log_it", "dbo",
                        "BEGIN INSERT INTO dbo.audit VALUES ('call') END").orElseThrow());
                st.execute(dialect.createTriggerUsingProcedure("items_insert", "dbo", TriggerTiming.AFTER,
                        TriggerEvent.INSERT, ITEMS, TriggerScope.STATEMENT, null, "log_it"));
                st.execute("INSERT INTO items VALUES (1)");
                assertThat(count(st, "SELECT COUNT(*) FROM audit")).as("the trigger calls it").isEqualTo(1);
                st.execute(dialect.dropProcedure("log_it", "dbo", true).orElseThrow());
                assertThat(exists(st, "log_it")).as("dropped though a trigger calls it").isFalse();
                assertThatThrownBy(() -> st.execute("INSERT INTO items VALUES (2)"))
                        .as("the trigger fails the insert").isInstanceOf(SQLException.class)
                        .hasMessageContaining("Could not find stored procedure");
                assertThat(count(st, "SELECT COUNT(*) FROM items")).as("the insert undone").isEqualTo(1);
            }
        }
    }

    private static boolean exists(Statement st, String name) throws SQLException {
        return count(st, "SELECT COUNT(*) FROM sys.procedures WHERE schema_id = SCHEMA_ID('dbo') AND name = N'"
                + name.replace("'", "''") + "'") > 0;
    }

    private static int count(Statement st, String sql) throws SQLException {
        try (ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }
}
