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
import org.eclipse.daanse.sql.dialect.api.generator.DdlGenerator.RoutineDefinition;
import org.eclipse.daanse.sql.dialect.api.generator.DdlGenerator.RoutineParameter;
import org.eclipse.daanse.sql.dialect.api.generator.DdlGenerator.RoutineSignature;
import org.eclipse.daanse.sql.dialect.db.mssqlserver.MicrosoftSqlServerDialect;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.containers.MSSQLServerContainer;

/**
 * SQL Server drops a function only if it exists, {@code DROP FUNCTION IF EXISTS s.f}, from 2016 on, so in every version
 * Docker serves, 2017 to 2025. The dialect is built from a live connection: it writes IF EXISTS, names the function by
 * its name alone, for SQL Server has no overloads, and the function it drops is gone; a second drop with IF EXISTS
 * passes, without it fails (Msg 3701). IF EXISTS spares no other error: a function a schema-bound view uses is not
 * dropped, and the drop of a name a procedure holds fails ("is a procedure") and leaves it.
 */
@EnabledIfSystemProperty(named = "integration.docker", matches = "true")
class MsSqlDropFunctionIfExistsTest {

    private static final RoutineSignature TWICE = new RoutineSignature("dbo", "twice", List.of("INT"));

    @ParameterizedTest(name = "mssql/server:{0}")
    @ValueSource(strings = { "2017-latest", "2019-latest", "2022-latest", "2025-latest" })
    void dropFunctionIfExists(String tag) throws Exception {
        try (MSSQLServerContainer<?> ms = new MSSQLServerContainer<>("mcr.microsoft.com/mssql/server:" + tag)
                .acceptLicense()) {
            ms.start();
            try (Connection c = DriverManager.getConnection(ms.getJdbcUrl(), ms.getUsername(), ms.getPassword());
                    Statement st = c.createStatement()) {
                MicrosoftSqlServerDialect dialect = new MicrosoftSqlServerDialect(DialectInitData.fromConnection(c));
                String create = dialect.createFunction(new RoutineDefinition("dbo", "twice",
                        List.of(RoutineParameter.in("a", "INT")), "INT", "tsql", "BEGIN RETURN @a * 2 END"), false)
                        .orElseThrow();

                String drop = dialect.dropFunction(TWICE, true).orElseThrow();
                assertThat(drop).as("IF EXISTS, the name alone").isEqualTo("DROP FUNCTION IF EXISTS \"dbo\".\"twice\"");
                String plain = dialect.dropFunction(TWICE, false).orElseThrow();
                assertThat(plain).isEqualTo("DROP FUNCTION \"dbo\".\"twice\"");

                st.execute(create);
                assertThat(exists(st, "twice", "FN")).as("created").isTrue();
                st.execute(drop);
                assertThat(exists(st, "twice", "FN")).as("dropped").isFalse();
                st.execute(drop);
                assertThatThrownBy(() -> st.execute(plain)).as("a missing one without IF EXISTS")
                        .isInstanceOf(SQLException.class).hasMessageContaining("does not exist");

                // a function a schema-bound view uses (the function bound too, which the dialect does not write):
                // IF EXISTS spares no other error
                st.execute("CREATE FUNCTION dbo.twice(@a INT) RETURNS INT WITH SCHEMABINDING AS BEGIN RETURN @a * 2 END");
                st.execute("CREATE VIEW dbo.doubled WITH SCHEMABINDING AS SELECT dbo.twice(21) AS v");
                assertThatThrownBy(() -> st.execute(drop)).as("used by a schema-bound view")
                        .isInstanceOf(SQLException.class).hasMessageContaining("referenced by");
                assertThat(exists(st, "twice", "FN")).as("left").isTrue();
                st.execute("DROP VIEW dbo.doubled");
                st.execute(drop);
                assertThat(exists(st, "twice", "FN")).as("dropped once the view is").isFalse();

                // a procedure of the name: IF EXISTS does not pass over it, the drop fails and leaves it
                st.execute("CREATE PROCEDURE dbo.twice AS SELECT 1");
                assertThatThrownBy(() -> st.execute(drop)).as("a procedure of the name")
                        .isInstanceOf(SQLException.class).hasMessageContaining("is a procedure");
                assertThat(exists(st, "twice", "P")).as("the procedure of the name left").isTrue();
            }
        }
    }

    private static boolean exists(Statement st, String name, String type) throws SQLException {
        try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM sys.objects WHERE schema_id = SCHEMA_ID('dbo')"
                + " AND name = '" + name + "' AND type = '" + type + "'")) {
            rs.next();
            return rs.getInt(1) > 0;
        }
    }
}
