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
 * SQL Server creates a function with {@code CREATE FUNCTION s.f (@a INT) RETURNS INT AS body}, and replaces one with
 * {@code CREATE OR ALTER FUNCTION} from 2016 on, so in every version Docker serves, 2017 to 2025. The dialect is built
 * from a live connection: the function it creates is called; created again without {@code orReplace} it is refused as
 * there; with it the new body is taken and the function stays the same object, so a right granted on it is kept, where
 * a drop and a new create would lose it. A function of no parameters, an inline function of a table and a parameter
 * named with its {@code @} are written as SQL Server reads them; a function of an OUT parameter, of no return type or
 * of a body in another language is not written.
 */
@EnabledIfSystemProperty(named = "integration.docker", matches = "true")
class MsSqlCreateFunctionTest {

    private static final String DOUBLED = "BEGIN RETURN @a * 2 END";
    private static final String TRIPLED = "BEGIN RETURN @a * 3 END";

    @ParameterizedTest(name = "mssql/server:{0}")
    @ValueSource(strings = { "2017-latest", "2019-latest", "2022-latest", "2025-latest" })
    void createFunction(String tag) throws Exception {
        try (MSSQLServerContainer<?> ms = new MSSQLServerContainer<>("mcr.microsoft.com/mssql/server:" + tag)
                .acceptLicense()) {
            ms.start();
            try (Connection c = DriverManager.getConnection(ms.getJdbcUrl(), ms.getUsername(), ms.getPassword());
                    Statement st = c.createStatement()) {
                MicrosoftSqlServerDialect dialect = new MicrosoftSqlServerDialect(DialectInitData.fromConnection(c));
                st.execute("CREATE TABLE nums(v INT)");
                st.execute("INSERT INTO nums VALUES (1), (2), (3)");

                String create = dialect.createFunction(twice(DOUBLED), false).orElseThrow();
                assertThat(create).isEqualTo("CREATE FUNCTION \"dbo\".\"twice\"(@a INT) RETURNS INT AS " + DOUBLED);
                st.execute(create);
                assertThat(value(st, "SELECT dbo.twice(21)")).as("called").isEqualTo("42");
                assertThatThrownBy(() -> st.execute(create)).as("created again without orReplace")
                        .isInstanceOf(SQLException.class).hasMessageContaining("already an object named");

                // a right granted on it, and the object it is
                st.execute("CREATE USER reader WITHOUT LOGIN");
                st.execute("GRANT EXECUTE ON dbo.twice TO reader");
                String objectId = value(st, "SELECT OBJECT_ID('dbo.twice')");

                String replace = dialect.createFunction(twice(TRIPLED), true).orElseThrow();
                assertThat(replace).as("orReplace").startsWith("CREATE OR ALTER FUNCTION \"dbo\".\"twice\"(@a INT)");
                st.execute(replace);
                assertThat(value(st, "SELECT dbo.twice(21)")).as("the new body taken").isEqualTo("63");
                assertThat(value(st, "SELECT OBJECT_ID('dbo.twice')")).as("the same object").isEqualTo(objectId);
                assertThat(rows(st, "SELECT permission_name FROM sys.database_permissions"
                        + " WHERE major_id = OBJECT_ID('dbo.twice') AND grantee_principal_id = USER_ID('reader')"))
                        .as("the right granted on it kept").containsExactly("EXECUTE");
                st.execute(dialect.createFunction(twice(DOUBLED), true).orElseThrow());
                assertThat(value(st, "SELECT dbo.twice(21)")).as("replaced again").isEqualTo("42");

                // no parameters; an inline function of a table; a parameter named with its @
                st.execute(dialect.createFunction(new RoutineDefinition("dbo", "answer", List.of(), "INT", "tsql",
                        "BEGIN RETURN 42 END"), false).orElseThrow());
                assertThat(value(st, "SELECT dbo.answer()")).as("no parameters").isEqualTo("42");
                st.execute(dialect.createFunction(new RoutineDefinition("dbo", "above",
                        List.of(RoutineParameter.in("@min", "INT")), "TABLE", "tsql",
                        "RETURN (SELECT v FROM dbo.nums WHERE v > @min)"), false).orElseThrow());
                assertThat(rows(st, "SELECT v FROM dbo.above(1) ORDER BY v")).as("an inline function of a table")
                        .containsExactly("2", "3");

                // not written: an OUT parameter, no return type, another language
                assertThat(dialect.createFunction(new RoutineDefinition("dbo", "f",
                        List.of(new RoutineParameter("a", ParameterMode.OUT, "INT")), "INT", "tsql", DOUBLED), false))
                        .as("an OUT parameter").isEmpty();
                assertThat(dialect.createFunction(new RoutineDefinition("dbo", "f",
                        List.of(RoutineParameter.in("a", "INT")), null, "tsql", DOUBLED), false))
                        .as("no return type").isEmpty();
                assertThat(dialect.createFunction(new RoutineDefinition("dbo", "f",
                        List.of(RoutineParameter.in("a", "INT")), "INT", "plpgsql", DOUBLED), false))
                        .as("another language").isEmpty();

                // dropped by its signature
                st.execute(dialect.dropFunction(new RoutineSignature("dbo", "twice", List.of("INT")), false)
                        .orElseThrow());
                assertThat(value(st, "SELECT COALESCE(CAST(OBJECT_ID('dbo.twice') AS VARCHAR), 'gone')"))
                        .as("dropped").isEqualTo("gone");
            }
        }
    }

    private static RoutineDefinition twice(String body) {
        return new RoutineDefinition("dbo", "twice", List.of(RoutineParameter.in("a", "INT")), "INT", "tsql", body);
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
