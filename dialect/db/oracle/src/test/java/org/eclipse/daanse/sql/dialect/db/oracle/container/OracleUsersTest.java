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
import java.util.ArrayList;
import java.util.List;

import org.eclipse.daanse.sql.dialect.api.DialectInitData;
import org.eclipse.daanse.sql.dialect.db.oracle.OracleDialect;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.testcontainers.containers.JdbcDatabaseContainer;

/**
 * Oracle creates a user without credentials with {@code CREATE USER x NO AUTHENTICATION}, from 18 on, and writes
 * {@code IF NOT EXISTS} for it, and {@code IF EXISTS} for its drop, from 23 on. The dialect is built from a live
 * connection of SYSTEM: on every version the user it creates is there, authenticated by nothing, cannot log in and is
 * dropped; from 23 on IF [NOT] EXISTS spares an error; on 18 and 21 it is left out, for the server refuses it, and a
 * second create fails with ORA-01920, a second drop with ORA-01918. Oracle 11g, which has no users without
 * credentials, is {@link OracleSupportsUsersTest}'s.
 */
@EnabledIfSystemProperty(named = "integration.docker", matches = "true")
class OracleUsersTest {

    private static final String USER = "RT_USER";

    @ParameterizedTest(name = "{0} -> IF [NOT] EXISTS {1}")
    @CsvSource({ "gvenzl/oracle-xe:18-slim-faststart, false", "gvenzl/oracle-xe:21-slim-faststart, false",
            "gvenzl/oracle-free:23.9-slim-faststart, true",
            "gvenzl/oracle-free:23.26.4-slim-faststart, true" })
    void users(String image, boolean ifExists) throws Exception {
        try (JdbcDatabaseContainer<?> ora = OracleContainers.of(image)) {
            ora.start();
            // SYSTEM may create users and read DBA_USERS; the container's user may not
            try (Connection c = DriverManager.getConnection(ora.getJdbcUrl(), "system", ora.getPassword());
                    Statement st = c.createStatement()) {
                OracleDialect dialect = new OracleDialect(DialectInitData.fromConnection(c));

                assertThat(dialect.supportsUsers()).as("supportsUsers from 18").isTrue();

                String create = dialect.createUser(USER, false).orElseThrow();
                assertThat(create).as("a plain create").isEqualTo("CREATE USER \"RT_USER\" NO AUTHENTICATION");
                st.execute(create);
                assertThat(rows(st, "SELECT AUTHENTICATION_TYPE FROM DBA_USERS WHERE USERNAME = '" + USER + "'"))
                        .as("the user, authenticated by nothing").containsExactly("NONE");

                assertThatThrownBy(() -> DriverManager.getConnection(ora.getJdbcUrl(), USER, "rt").close())
                        .as("a user without credentials does not log in").isInstanceOf(SQLException.class);

                assertThatThrownBy(() -> st.execute(create)).as("a second one of the name")
                        .isInstanceOf(SQLException.class).hasMessageContaining("ORA-01920");

                String createIfNotExists = dialect.createUser(USER, true).orElseThrow();
                String dropIfExists = dialect.dropUser(USER, true).orElseThrow();
                if (ifExists) {
                    assertThat(createIfNotExists).as("IF NOT EXISTS from 23")
                            .isEqualTo("CREATE USER IF NOT EXISTS \"RT_USER\" NO AUTHENTICATION");
                    assertThat(dropIfExists).as("IF EXISTS from 23").isEqualTo("DROP USER IF EXISTS \"RT_USER\"");
                    st.execute(createIfNotExists);
                } else {
                    assertThat(createIfNotExists).as("no IF NOT EXISTS before 23").isEqualTo(create);
                    assertThat(dropIfExists).as("no IF EXISTS before 23").isEqualTo("DROP USER \"RT_USER\"");
                    assertThatThrownBy(() -> st.execute("CREATE USER IF NOT EXISTS " + USER + " NO AUTHENTICATION"))
                            .as("IF NOT EXISTS is refused before 23").isInstanceOf(SQLException.class);
                    assertThatThrownBy(() -> st.execute(createIfNotExists)).as("a second one, plainly")
                            .isInstanceOf(SQLException.class).hasMessageContaining("ORA-01920");
                }

                String drop = dialect.dropUser(USER, false).orElseThrow();
                st.execute(drop);
                assertThat(rows(st, "SELECT USERNAME FROM DBA_USERS WHERE USERNAME = '" + USER + "'"))
                        .as("dropped").isEmpty();
                assertThatThrownBy(() -> st.execute(drop)).as("a missing one").isInstanceOf(SQLException.class)
                        .hasMessageContaining("ORA-01918");
                if (ifExists) {
                    st.execute(dropIfExists);
                } else {
                    assertThatThrownBy(() -> st.execute(dropIfExists)).as("a missing one, plainly")
                            .isInstanceOf(SQLException.class).hasMessageContaining("ORA-01918");
                }
            }
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
