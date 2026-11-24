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
 * Oracle drops a user only if it exists, {@code DROP USER IF EXISTS x}, from 23 (23ai) on. The dialect is built from a
 * live connection of SYSTEM: from 23 on it writes IF EXISTS, the user it drops is gone and the drop of a missing one
 * passes; before, on 18 and 21, it writes a plain DROP USER, for the server refuses IF EXISTS, and the drop of a
 * missing user fails with ORA-01918.
 */
@EnabledIfSystemProperty(named = "integration.docker", matches = "true")
class OracleDropUserIfExistsTest {

    private static final String USER = "RT_USER";

    @ParameterizedTest(name = "{0} -> DROP USER IF EXISTS {1}")
    @CsvSource({ "gvenzl/oracle-xe:18-slim-faststart, false", "gvenzl/oracle-xe:21-slim-faststart, false",
            "gvenzl/oracle-free:23.9-slim-faststart, true",
            "gvenzl/oracle-free:23.26.4-slim-faststart, true" })
    void dropUserIfExists(String image, boolean expected) throws Exception {
        try (JdbcDatabaseContainer<?> ora = OracleContainers.of(image)) {
            ora.start();
            // SYSTEM may create and drop users and read DBA_USERS; the container's user may not
            try (Connection c = DriverManager.getConnection(ora.getJdbcUrl(), "system", ora.getPassword());
                    Statement st = c.createStatement()) {
                OracleDialect dialect = new OracleDialect(DialectInitData.fromConnection(c));
                String drop = dialect.dropUser(USER, true).orElseThrow();

                st.execute(dialect.createUser(USER, false).orElseThrow());
                assertThat(exists(st)).as("created").isTrue();

                if (!expected) {
                    assertThat(drop).as("a plain drop before 23").isEqualTo("DROP USER \"RT_USER\"");
                    assertThatThrownBy(() -> st.execute("DROP USER IF EXISTS " + USER))
                            .as("IF EXISTS is refused before 23").isInstanceOf(SQLException.class);
                    assertThat(exists(st)).as("the refused drop leaves the user").isTrue();
                    st.execute(drop);
                    assertThat(exists(st)).as("dropped").isFalse();
                    assertThatThrownBy(() -> st.execute(drop)).as("a missing one")
                            .isInstanceOf(SQLException.class).hasMessageContaining("ORA-01918");
                    return;
                }

                assertThat(drop).as("IF EXISTS from 23").isEqualTo("DROP USER IF EXISTS \"RT_USER\"");
                st.execute(drop);
                assertThat(exists(st)).as("dropped").isFalse();
                st.execute(drop);
                assertThatThrownBy(() -> st.execute(dialect.dropUser(USER, false).orElseThrow()))
                        .as("a missing one without IF EXISTS").isInstanceOf(SQLException.class)
                        .hasMessageContaining("ORA-01918");
            }
        }
    }

    private static boolean exists(Statement st) throws SQLException {
        try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM DBA_USERS WHERE USERNAME = '" + USER + "'")) {
            rs.next();
            return rs.getInt(1) > 0;
        }
    }
}
