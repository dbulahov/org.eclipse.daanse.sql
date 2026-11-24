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

import org.eclipse.daanse.sql.dialect.api.DialectInitData;
import org.eclipse.daanse.sql.dialect.db.oracle.OracleDialect;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.testcontainers.containers.JdbcDatabaseContainer;

/**
 * Oracle creates a user without credentials, {@code CREATE USER x NO AUTHENTICATION}, a schema-only account, from 18
 * on. The dialect is built from a live connection of SYSTEM: from 18 on the user it creates is there, authenticated by
 * nothing, and cannot log in until it is given a password and the right to log in, when it does; on 11g it writes no
 * user, for the server refuses NO AUTHENTICATION.
 */
@EnabledIfSystemProperty(named = "integration.docker", matches = "true")
class OracleSupportsUsersTest {

    private static final String USER = "RT_USER";

    @ParameterizedTest(name = "{0} -> users {1}")
    @CsvSource({ "gvenzl/oracle-xe:11-slim, false", "gvenzl/oracle-xe:18-slim-faststart, true",
            "gvenzl/oracle-xe:21-slim-faststart, true", "gvenzl/oracle-free:23.9-slim-faststart, true",
            "gvenzl/oracle-free:23.26.4-slim-faststart, true" })
    void supportsUsers(String image, boolean expected) throws Exception {
        try (JdbcDatabaseContainer<?> ora = OracleContainers.of(image)) {
            ora.start();
            // SYSTEM may create users and read DBA_USERS; the container's user may not
            try (Connection c = DriverManager.getConnection(ora.getJdbcUrl(), "system", ora.getPassword());
                    Statement st = c.createStatement()) {
                OracleDialect dialect = new OracleDialect(DialectInitData.fromConnection(c));

                assertThat(dialect.supportsUsers()).as("supportsUsers").isEqualTo(expected);

                if (!expected) {
                    assertThat(dialect.createUser(USER, false)).as("no user before 18").isEmpty();
                    assertThat(dialect.createUser(USER, true)).as("no user before 18").isEmpty();
                    assertThat(dialect.dropUser(USER, true)).as("no user before 18").isEmpty();
                    assertThatThrownBy(() -> st.execute("CREATE USER " + USER + " NO AUTHENTICATION"))
                            .as("NO AUTHENTICATION is refused before 18").isInstanceOf(SQLException.class);
                    assertThat(exists(st)).as("the refused create wrote no user").isFalse();
                    return;
                }

                st.execute(dialect.createUser(USER, false).orElseThrow());
                assertThat(authentication(st)).as("the user, authenticated by nothing").isEqualTo("NONE");
                assertThatThrownBy(() -> DriverManager.getConnection(ora.getJdbcUrl(), USER, "rt").close())
                        .as("a user without credentials does not log in").isInstanceOf(SQLException.class);

                st.execute("ALTER USER " + USER + " IDENTIFIED BY rt");
                st.execute("GRANT CREATE SESSION TO " + USER);
                assertThat(authentication(st)).as("given a password").isEqualTo("PASSWORD");
                try (Connection u = DriverManager.getConnection(ora.getJdbcUrl(), USER, "rt");
                        Statement us = u.createStatement(); ResultSet rs = us.executeQuery("SELECT USER FROM DUAL")) {
                    rs.next();
                    assertThat(rs.getString(1)).as("it logs in then").isEqualTo(USER);
                }

                st.execute(dialect.dropUser(USER, false).orElseThrow());
                assertThat(exists(st)).as("dropped").isFalse();
            }
        }
    }

    private static String authentication(Statement st) throws SQLException {
        try (ResultSet rs = st.executeQuery(
                "SELECT AUTHENTICATION_TYPE FROM DBA_USERS WHERE USERNAME = '" + USER + "'")) {
            rs.next();
            return rs.getString(1);
        }
    }

    private static boolean exists(Statement st) throws SQLException {
        try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM DBA_USERS WHERE USERNAME = '" + USER + "'")) {
            rs.next();
            return rs.getInt(1) > 0;
        }
    }
}
