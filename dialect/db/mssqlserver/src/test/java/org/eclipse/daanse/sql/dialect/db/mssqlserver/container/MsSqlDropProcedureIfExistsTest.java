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

import org.eclipse.daanse.sql.dialect.api.DialectInitData;
import org.eclipse.daanse.sql.dialect.api.generator.DdlGenerator.ParameterMode;
import org.eclipse.daanse.sql.dialect.api.generator.DdlGenerator.RoutineDefinition;
import org.eclipse.daanse.sql.dialect.api.generator.DdlGenerator.RoutineParameter;
import org.eclipse.daanse.sql.dialect.api.generator.DdlGenerator.RoutineSignature;
import org.eclipse.daanse.sql.dialect.db.mssqlserver.MicrosoftSqlServerDialect;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.containers.MSSQLServerContainer;

/**
 * SQL Server drops a procedure only if it exists, {@code DROP PROCEDURE IF EXISTS s.p}, from 2016 on, so in every
 * version Docker serves, 2017 to 2025. The dialect is built from a live connection: it writes IF EXISTS, names the
 * procedure by its name alone, for SQL Server has no overloads, and the procedure it drops is gone; a second drop with
 * IF EXISTS passes, without it fails (Msg 3701). IF EXISTS spares no other error: the drop of a name a function holds
 * fails and leaves it. The trigger procedures' drop by schema and name writes the same statement.
 */
@EnabledIfSystemProperty(named = "integration.docker", matches = "true")
class MsSqlDropProcedureIfExistsTest {

    private static final RoutineSignature ADD_TO = new RoutineSignature("dbo", "add_to", List.of("INT", "INT"));

    @ParameterizedTest(name = "mssql/server:{0}")
    @ValueSource(strings = { "2017-latest", "2019-latest", "2022-latest", "2025-latest" })
    void dropProcedureIfExists(String tag) throws Exception {
        try (MSSQLServerContainer<?> ms = new MSSQLServerContainer<>("mcr.microsoft.com/mssql/server:" + tag)
                .acceptLicense()) {
            ms.start();
            try (Connection c = DriverManager.getConnection(ms.getJdbcUrl(), ms.getUsername(), ms.getPassword());
                    Statement st = c.createStatement()) {
                MicrosoftSqlServerDialect dialect = new MicrosoftSqlServerDialect(DialectInitData.fromConnection(c));
                String create = dialect.createProcedure(new RoutineDefinition("dbo", "add_to",
                        List.of(RoutineParameter.in("n", "INT"),
                                new RoutineParameter("total", ParameterMode.INOUT, "INT")),
                        null, "tsql", "BEGIN SET @total = @total + @n END"), false).orElseThrow();

                String drop = dialect.dropProcedure(ADD_TO, true).orElseThrow();
                assertThat(drop).as("IF EXISTS, the name alone")
                        .isEqualTo("DROP PROCEDURE IF EXISTS \"dbo\".\"add_to\"");
                String plain = dialect.dropProcedure(ADD_TO, false).orElseThrow();
                assertThat(plain).isEqualTo("DROP PROCEDURE \"dbo\".\"add_to\"");
                assertThat(dialect.dropProcedure("add_to", "dbo", true)).as("the drop by schema and name")
                        .contains(drop);
                assertThat(dialect.dropProcedure("add_to", "dbo", false)).contains(plain);

                st.execute(create);
                assertThat(exists(st, "P")).as("created").isTrue();
                st.execute(drop);
                assertThat(exists(st, "P")).as("dropped").isFalse();
                st.execute(drop);
                assertThatThrownBy(() -> st.execute(plain)).as("a missing one without IF EXISTS")
                        .isInstanceOf(SQLException.class).hasMessageContaining("does not exist");

                // a function of the name: IF EXISTS does not pass over it, the drop fails and leaves it
                st.execute("CREATE FUNCTION dbo.add_to(@n INT) RETURNS INT AS BEGIN RETURN @n END");
                assertThatThrownBy(() -> st.execute(drop)).as("a function of the name")
                        .isInstanceOf(SQLException.class).hasMessageContaining("is a function");
                assertThat(exists(st, "FN")).as("the function of the name left").isTrue();
            }
        }
    }

    private static boolean exists(Statement st, String type) throws SQLException {
        try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM sys.objects WHERE schema_id = SCHEMA_ID('dbo')"
                + " AND name = 'add_to' AND type = '" + type + "'")) {
            rs.next();
            return rs.getInt(1) > 0;
        }
    }
}
