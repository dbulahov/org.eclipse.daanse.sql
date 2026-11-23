/*
 * Copyright (c) 2026 Contributors to the Eclipse Foundation.
 *
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.daanse.sql.dialect.db.postgresql.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
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
import org.eclipse.daanse.sql.dialect.db.postgresql.PostgreSqlDialect;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * The dialect is built from a live connection, and each function it writes must be created and called on the server:
 * in SQL and in PL/pgSQL, with a return type, with {@code OUT} parameters that make the result, returning a table,
 * replaced, overloaded and dropped by its types. {@code DROP FUNCTION name} without the types came with PostgreSQL 10;
 * before that the dialect must write no such statement, for the server would refuse it.
 */
@EnabledIfSystemProperty(named = "integration.docker", matches = "true")
class PostgreSqlCreateFunctionTest {

    /** A SQL body names its parameters by position: by name only since 9.2. */
    private static final RoutineDefinition ADD = function("add", "integer", "sql", "SELECT $1 + $2",
            RoutineParameter.in("a", "integer"), RoutineParameter.in("b", "integer"));

    private static final RoutineDefinition ADD_PLUS_ONE = function("add", "integer", "sql", "SELECT $1 + $2 + 1",
            RoutineParameter.in("a", "integer"), RoutineParameter.in("b", "integer"));

    /** An overload of add, of two texts. */
    private static final RoutineDefinition ADD_TEXT = function("add", "text", "sql", "SELECT $1 || $2",
            RoutineParameter.in("a", "text"), RoutineParameter.in("b", "text"));

    private static final RoutineDefinition MUL = function("mul", "integer", "plpgsql", "BEGIN RETURN a * b; END",
            RoutineParameter.in("a", "integer"), RoutineParameter.in("b", "integer"));

    /** No return type: the OUT parameters make the result. */
    private static final RoutineDefinition DIVMOD = function("divmod", null, "plpgsql",
            "BEGIN q := a / b; r := a % b; END", RoutineParameter.in("a", "integer"),
            RoutineParameter.in("b", "integer"), new RoutineParameter("q", ParameterMode.OUT, "integer"),
            new RoutineParameter("r", ParameterMode.OUT, "integer"));

    private static final RoutineDefinition UPTO = function("upto", "TABLE(n integer)", "sql",
            "SELECT generate_series(1, $1)", RoutineParameter.in("k", "integer"));

    /** A body that holds {@code $$} itself, which the dialect must quote with another dollar quote. */
    private static final RoutineDefinition DOLLAR = function("dollar", "text", "sql", "SELECT $$x$$::text");

    @ParameterizedTest(name = "postgres:{0} -> DROP FUNCTION without types {1}")
    @CsvSource({ "9.1, false", "9.6, false", "10-alpine, true", "16-alpine, true" })
    void create_function(String tag, boolean dropByName) throws Exception {
        try (PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:" + tag)) {
            pg.start();
            try (Connection c = pg.createConnection(""); Statement st = c.createStatement()) {
                st.execute("CREATE SCHEMA rt");
                PostgreSqlDialect dialect = new PostgreSqlDialect(DialectInitData.fromConnection(c));

                st.execute(sql(dialect, ADD, false));
                assertThat(rows(st, "SELECT rt.add(2, 3)")).containsExactly("5");

                assertThatThrownBy(() -> st.execute(sql(dialect, ADD_PLUS_ONE, false)))
                        .as("without OR REPLACE the second one fails").isInstanceOf(SQLException.class)
                        .hasMessageContaining("already exists");
                st.execute(sql(dialect, ADD_PLUS_ONE, true));
                assertThat(rows(st, "SELECT rt.add(2, 3)")).as("OR REPLACE took the new body").containsExactly("6");

                st.execute(sql(dialect, MUL, false));
                assertThat(rows(st, "SELECT rt.mul(4, 5)")).as("PL/pgSQL, by the parameters' names")
                        .containsExactly("20");

                st.execute(sql(dialect, DIVMOD, false));
                assertThat(rows(st, "SELECT q || '/' || r FROM rt.divmod(17, 5)")).as("OUT parameters as the result")
                        .containsExactly("3/2");

                st.execute(sql(dialect, UPTO, false));
                assertThat(rows(st, "SELECT n FROM rt.upto(3)")).as("a table").containsExactly("1", "2", "3");

                String dollar = sql(dialect, DOLLAR, false);
                assertThat(dollar).contains("$body$");
                st.execute(dollar);
                assertThat(rows(st, "SELECT rt.dollar()")).containsExactly("x");

                assertThat(dialect.createFunction(function("f", "integer", "java", "x"), false))
                        .as("a body in a language the dialect does not write").isEmpty();

                st.execute(sql(dialect, ADD_TEXT, false));
                st.execute(dialect.dropFunction(signature("add", "integer", "integer"), false).orElseThrow());
                assertThatThrownBy(() -> rows(st, "SELECT rt.add(2, 3)")).as("the integer overload is dropped")
                        .isInstanceOf(SQLException.class).hasMessageContaining("does not exist");
                assertThat(rows(st, "SELECT rt.add('x', 'y')")).as("the text one is left").containsExactly("xy");
                st.execute(dialect.dropFunction(signature("add", "integer", "integer"), true).orElseThrow());

                var byName = dialect.dropFunction(signature("mul"), false);
                if (dropByName) {
                    st.execute(byName.orElseThrow());
                    assertThatThrownBy(() -> rows(st, "SELECT rt.mul(4, 5)")).as("dropped by its name")
                            .isInstanceOf(SQLException.class).hasMessageContaining("does not exist");
                    st.execute(dialect.dropFunction(signature("mul"), true).orElseThrow());
                } else {
                    assertThat(byName).as("no DROP FUNCTION without the types before 10").isEmpty();
                    assertThatThrownBy(() -> st.execute("DROP FUNCTION rt.mul"))
                            .as("the server refuses it").isInstanceOf(SQLException.class)
                            .hasMessageContaining("syntax error");
                }
            }
        }
    }

    private static RoutineDefinition function(String name, String returnType, String language, String body,
            RoutineParameter... parameters) {
        return new RoutineDefinition("rt", name, List.of(parameters), returnType, language, body);
    }

    private static RoutineSignature signature(String name, String... types) {
        return new RoutineSignature("rt", name, types.length == 0 ? null : List.of(types));
    }

    private static String sql(PostgreSqlDialect dialect, RoutineDefinition function, boolean orReplace) {
        return dialect.createFunction(function, orReplace).orElseThrow();
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
