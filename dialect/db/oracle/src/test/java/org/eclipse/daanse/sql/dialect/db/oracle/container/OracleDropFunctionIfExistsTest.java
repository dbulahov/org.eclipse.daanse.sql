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
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import org.eclipse.daanse.sql.dialect.api.DialectInitData;
import org.eclipse.daanse.sql.dialect.api.generator.DdlGenerator.RoutineDefinition;
import org.eclipse.daanse.sql.dialect.api.generator.DdlGenerator.RoutineParameter;
import org.eclipse.daanse.sql.dialect.api.generator.DdlGenerator.RoutineSignature;
import org.eclipse.daanse.sql.dialect.db.oracle.OracleDialect;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.testcontainers.containers.JdbcDatabaseContainer;

/**
 * Oracle drops a function only if it exists, {@code DROP FUNCTION IF EXISTS}, from 23 (23ai) on. The dialect is built
 * from a live connection: on every version the function it creates is called and named by its name alone, for a schema
 * holds one routine of a name; from 23 on it writes IF EXISTS, the function it drops is gone and the drop of a missing
 * one passes; before, on 18 and 21, it writes a plain DROP FUNCTION, for the server refuses IF EXISTS, and the drop of
 * a missing function fails with ORA-04043.
 */
@EnabledIfSystemProperty(named = "integration.docker", matches = "true")
class OracleDropFunctionIfExistsTest {

    @ParameterizedTest(name = "{0} -> DROP FUNCTION IF EXISTS {1}")
    @CsvSource({ "gvenzl/oracle-xe:18-slim-faststart, false", "gvenzl/oracle-xe:21-slim-faststart, false",
            "gvenzl/oracle-free:23.9-slim-faststart, true",
            "gvenzl/oracle-free:23.26.4-slim-faststart, true" })
    void dropFunctionIfExists(String image, boolean expected) throws Exception {
        try (JdbcDatabaseContainer<?> ora = OracleContainers.of(image)) {
            ora.start();
            try (Connection c = DriverManager.getConnection(ora.getJdbcUrl(), ora.getUsername(), ora.getPassword());
                    Statement st = c.createStatement()) {
                String schema = ora.getUsername().toUpperCase();
                OracleDialect dialect = new OracleDialect(DialectInitData.fromConnection(c));

                st.execute(dialect.createFunction(new RoutineDefinition(schema, "TWICE",
                        List.of(RoutineParameter.in("X", "NUMBER")), "NUMBER", "plsql",
                        "BEGIN RETURN \"X\" * 2; END;"), false).orElseThrow());
                assertThat(exists(st)).as("created").isTrue();
                assertThat(call(st)).as("called").isEqualTo(42);

                // the types of its parameters are not written: a schema holds one function of a name
                RoutineSignature twice = new RoutineSignature(schema, "TWICE", List.of("NUMBER"));
                String drop = dialect.dropFunction(twice, true).orElseThrow();

                if (!expected) {
                    assertThat(drop).as("a plain drop before 23")
                            .isEqualTo("DROP FUNCTION \"" + schema + "\".\"TWICE\"");
                    assertThatThrownBy(() -> st.execute("DROP FUNCTION IF EXISTS TWICE"))
                            .as("IF EXISTS is refused before 23").isInstanceOf(SQLException.class);
                    assertThat(exists(st)).as("the refused drop leaves the function").isTrue();
                    st.execute(drop);
                    assertThat(exists(st)).as("dropped").isFalse();
                    assertThatThrownBy(() -> st.execute(drop)).as("a missing one")
                            .isInstanceOf(SQLException.class).hasMessageContaining("ORA-04043");
                    return;
                }

                assertThat(drop).as("IF EXISTS from 23")
                        .isEqualTo("DROP FUNCTION IF EXISTS \"" + schema + "\".\"TWICE\"");
                st.execute(drop);
                assertThat(exists(st)).as("dropped").isFalse();
                assertThatThrownBy(() -> call(st)).as("no longer called").isInstanceOf(SQLException.class);
                st.execute(drop);
                assertThatThrownBy(() -> st.execute(dialect.dropFunction(twice, false).orElseThrow()))
                        .as("a missing one without IF EXISTS").isInstanceOf(SQLException.class)
                        .hasMessageContaining("ORA-04043");
            }
        }
    }

    private static int call(Statement st) throws SQLException {
        try (ResultSet rs = st.executeQuery("SELECT TWICE(21) FROM DUAL")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private static boolean exists(Statement st) throws SQLException {
        try (ResultSet rs = st.executeQuery(
                "SELECT COUNT(*) FROM USER_OBJECTS WHERE OBJECT_TYPE = 'FUNCTION' AND OBJECT_NAME = 'TWICE'")) {
            rs.next();
            return rs.getInt(1) > 0;
        }
    }
}
