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

import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
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
 * SQL Server creates a procedure with {@code CREATE PROCEDURE s.p @n INT, @total INT OUTPUT AS body}, and replaces one
 * with {@code CREATE OR ALTER PROCEDURE} from 2016 on, so in every version Docker serves, 2017 to 2025. The dialect is
 * built from a live connection: the procedure it creates is called, an OUT and an INOUT parameter, both OUTPUT ones,
 * carrying their values back; created again without {@code orReplace} it is refused as there; with it the new body is
 * taken and the procedure stays the same object, so a right granted on it is kept. A procedure of no parameters that
 * returns rows is written as SQL Server reads it; a procedure of a body in another language is not written.
 */
@EnabledIfSystemProperty(named = "integration.docker", matches = "true")
class MsSqlCreateProcedureTest {

    private static final String ADD = "BEGIN SET @total = @total + @n; SET @doubled = @n * 2 END";
    private static final String ADD_TWICE = "BEGIN SET @total = @total + 2 * @n; SET @doubled = @n * 2 END";

    @ParameterizedTest(name = "mssql/server:{0}")
    @ValueSource(strings = { "2017-latest", "2019-latest", "2022-latest", "2025-latest" })
    void createProcedure(String tag) throws Exception {
        try (MSSQLServerContainer<?> ms = new MSSQLServerContainer<>("mcr.microsoft.com/mssql/server:" + tag)
                .acceptLicense()) {
            ms.start();
            try (Connection c = DriverManager.getConnection(ms.getJdbcUrl(), ms.getUsername(), ms.getPassword());
                    Statement st = c.createStatement()) {
                MicrosoftSqlServerDialect dialect = new MicrosoftSqlServerDialect(DialectInitData.fromConnection(c));
                st.execute("CREATE TABLE nums(v INT)");
                st.execute("INSERT INTO nums VALUES (1), (2), (3)");

                String create = dialect.createProcedure(addTo(ADD), false).orElseThrow();
                assertThat(create).isEqualTo("CREATE PROCEDURE \"dbo\".\"add_to\" @n INT, @total INT OUTPUT,"
                        + " @doubled INT OUTPUT AS " + ADD);
                st.execute(create);
                assertThat(call(c)).as("called: the INOUT and the OUT value back").containsExactly(42, 4);
                assertThatThrownBy(() -> st.execute(create)).as("created again without orReplace")
                        .isInstanceOf(SQLException.class).hasMessageContaining("already an object named");

                // a right granted on it, and the object it is
                st.execute("CREATE USER runner WITHOUT LOGIN");
                st.execute("GRANT EXECUTE ON dbo.add_to TO runner");
                String objectId = value(st, "SELECT OBJECT_ID('dbo.add_to')");

                String replace = dialect.createProcedure(addTo(ADD_TWICE), true).orElseThrow();
                assertThat(replace).as("orReplace").startsWith("CREATE OR ALTER PROCEDURE \"dbo\".\"add_to\" @n INT");
                st.execute(replace);
                assertThat(call(c)).as("the new body taken").containsExactly(44, 4);
                assertThat(value(st, "SELECT OBJECT_ID('dbo.add_to')")).as("the same object").isEqualTo(objectId);
                assertThat(rows(st, "SELECT permission_name FROM sys.database_permissions"
                        + " WHERE major_id = OBJECT_ID('dbo.add_to') AND grantee_principal_id = USER_ID('runner')"))
                        .as("the right granted on it kept").containsExactly("EXECUTE");

                // no parameters, rows returned
                String listing = dialect.createProcedure(new RoutineDefinition("dbo", "list_nums", List.of(), null,
                        "tsql", "SELECT v FROM dbo.nums ORDER BY v"), false).orElseThrow();
                assertThat(listing)
                        .isEqualTo("CREATE PROCEDURE \"dbo\".\"list_nums\" AS SELECT v FROM dbo.nums ORDER BY v");
                st.execute(listing);
                assertThat(rows(st, "EXEC dbo.list_nums")).as("no parameters, rows returned")
                        .containsExactly("1", "2", "3");

                // not written: a body in another language
                assertThat(dialect.createProcedure(new RoutineDefinition("dbo", "p", List.of(), null, "plpgsql",
                        "BEGIN END"), false)).as("another language").isEmpty();

                // dropped by its signature
                st.execute(dialect.dropProcedure(new RoutineSignature("dbo", "add_to", List.of("INT", "INT", "INT")),
                        false).orElseThrow());
                assertThat(value(st, "SELECT COALESCE(CAST(OBJECT_ID('dbo.add_to') AS VARCHAR), 'gone')"))
                        .as("dropped").isEqualTo("gone");
            }
        }
    }

    /** add_to(@n IN, @total INOUT, @doubled OUT). */
    private static RoutineDefinition addTo(String body) {
        return new RoutineDefinition("dbo", "add_to", List.of(RoutineParameter.in("n", "INT"),
                new RoutineParameter("total", ParameterMode.INOUT, "INT"),
                new RoutineParameter("doubled", ParameterMode.OUT, "INT")), null, "tsql", body);
    }

    /** Calls add_to with n = 2 and total = 40; the total and the doubled n it gives back. */
    private static List<Integer> call(Connection c) throws SQLException {
        try (CallableStatement cs = c.prepareCall("{call dbo.add_to(?, ?, ?)}")) {
            cs.setInt(1, 2);
            cs.setInt(2, 40);
            cs.registerOutParameter(2, Types.INTEGER);
            cs.registerOutParameter(3, Types.INTEGER);
            cs.execute();
            return List.of(cs.getInt(2), cs.getInt(3));
        }
    }

    private static String value(Statement st, String sql) throws SQLException {
        try (ResultSet rs = st.executeQuery(sql)) {
            assertThat(rs.next()).isTrue();
            return rs.getString(1);
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
