/*
 * Copyright (c) 2026 Contributors to the Eclipse Foundation.
 *
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.daanse.sql.dialect.db.postgresql.container;

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
 * PostgreSQL has procedures since 11, and {@code OUT} parameters of a procedure since 14; before that a procedure
 * passes a value back through {@code INOUT} only. The dialect is built from a live connection: each procedure it writes
 * must be created and called on the server, and where the server has no procedures, or none with an {@code OUT}
 * parameter, it must write none.
 */
@EnabledIfSystemProperty(named = "integration.docker", matches = "true")
class PostgreSqlCreateProcedureTest {

    private static final String SETUP = """
            CREATE SCHEMA rt;
            CREATE TABLE rt.log(msg VARCHAR(20));
            """;

    /** {@code total := total + n}, the sum passed back through the INOUT parameter. */
    private static final RoutineDefinition ADD_TO = plpgsql("add_to", "BEGIN total := total + n; END",
            RoutineParameter.in("n", "integer"), new RoutineParameter("total", ParameterMode.INOUT, "integer"));

    /** The same name, the same signature, another body: it doubles. */
    private static final RoutineDefinition ADD_TO_TWICE = plpgsql("add_to", "BEGIN total := total + 2 * n; END",
            RoutineParameter.in("n", "integer"), new RoutineParameter("total", ParameterMode.INOUT, "integer"));

    /** A body in SQL that writes a row. */
    private static final RoutineDefinition LOG_SQL = new RoutineDefinition("rt", "log_it",
            List.of(RoutineParameter.in("m", "varchar")), null, "sql", "INSERT INTO rt.log VALUES (m)");

    /** A body that holds {@code $$} itself, which the dialect must quote with another dollar quote. */
    private static final RoutineDefinition LOG_DOLLAR = plpgsql("log_dollar",
            "BEGIN INSERT INTO rt.log VALUES ($$dollar$$); END");

    /** An OUT parameter of a procedure. */
    private static final RoutineDefinition SQUARE = plpgsql("square", "BEGIN r := n * n; END",
            RoutineParameter.in("n", "integer"), new RoutineParameter("r", ParameterMode.OUT, "integer"));

    @ParameterizedTest(name = "postgres:{0} -> procedures {1}, OUT {2}")
    @CsvSource({ "10-alpine, false, false", "11-alpine, true, false", "13-alpine, true, false",
            "14-alpine, true, true", "16-alpine, true, true" })
    void create_procedure(String tag, boolean procedures, boolean outParameters) throws Exception {
        try (PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:" + tag)) {
            pg.start();
            try (Connection c = pg.createConnection(""); Statement st = c.createStatement()) {
                st.execute(SETUP);
                PostgreSqlDialect dialect = new PostgreSqlDialect(DialectInitData.fromConnection(c));

                if (!procedures) {
                    assertThat(dialect.createProcedure(ADD_TO, false)).as("no procedure on 10").isEmpty();
                    assertThat(dialect.dropProcedure(signature("add_to"), true)).as("no DROP PROCEDURE on 10")
                            .isEmpty();
                    assertThatThrownBy(() -> st.execute(
                            "CREATE PROCEDURE rt.p() LANGUAGE sql AS $$ SELECT 1 $$"))
                            .as("PostgreSQL 10 knows no CREATE PROCEDURE").isInstanceOf(SQLException.class)
                            .hasMessageContaining("syntax error");
                    return;
                }

                st.execute(sql(dialect, ADD_TO, false));
                assertThat(rows(st, "CALL rt.add_to(5, 10)")).as("INOUT passes the sum back").containsExactly("15");

                assertThatThrownBy(() -> st.execute(sql(dialect, ADD_TO_TWICE, false)))
                        .as("without OR REPLACE the second one fails").isInstanceOf(SQLException.class)
                        .hasMessageContaining("already exists");
                st.execute(sql(dialect, ADD_TO_TWICE, true));
                assertThat(rows(st, "CALL rt.add_to(5, 10)")).as("OR REPLACE took the new body").containsExactly("20");

                st.execute(sql(dialect, LOG_SQL, false));
                st.execute("CALL rt.log_it('sql')");
                String dollar = sql(dialect, LOG_DOLLAR, false);
                assertThat(dollar).contains("$body$");
                st.execute(dollar);
                st.execute("CALL rt.log_dollar()");
                assertThat(rows(st, "SELECT msg FROM rt.log ORDER BY msg")).containsExactly("dollar", "sql");

                assertThat(dialect.createProcedure(
                        new RoutineDefinition("rt", "p", List.of(), null, "java", "x"), false))
                        .as("a body in a language the dialect does not write").isEmpty();

                if (outParameters) {
                    st.execute(sql(dialect, SQUARE, false));
                    assertThat(rows(st, "CALL rt.square(7, NULL)")).as("OUT passes the square back")
                            .containsExactly("49");
                } else {
                    assertThat(dialect.createProcedure(SQUARE, false)).as("no OUT parameter of a procedure before 14")
                            .isEmpty();
                    assertThatThrownBy(() -> st.execute(
                            "CREATE PROCEDURE rt.sq(n integer, OUT r integer) LANGUAGE sql AS $$ SELECT n * n $$"))
                            .as("the server refuses it").isInstanceOf(SQLException.class)
                            .hasMessageContaining("OUT");
                }

                st.execute(dialect.dropProcedure(signature("add_to", "integer", "integer"), false).orElseThrow());
                assertThatThrownBy(() -> st.execute("CALL rt.add_to(5, 10)")).as("dropped")
                        .isInstanceOf(SQLException.class).hasMessageContaining("does not exist");
                st.execute(dialect.dropProcedure(signature("add_to", "integer", "integer"), true).orElseThrow());
            }
        }
    }

    private static RoutineDefinition plpgsql(String name, String body, RoutineParameter... parameters) {
        return new RoutineDefinition("rt", name, List.of(parameters), null, "plpgsql", body);
    }

    private static RoutineSignature signature(String name, String... types) {
        return new RoutineSignature("rt", name, types.length == 0 ? null : List.of(types));
    }

    private static String sql(PostgreSqlDialect dialect, RoutineDefinition procedure, boolean orReplace) {
        return dialect.createProcedure(procedure, orReplace).orElseThrow();
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
