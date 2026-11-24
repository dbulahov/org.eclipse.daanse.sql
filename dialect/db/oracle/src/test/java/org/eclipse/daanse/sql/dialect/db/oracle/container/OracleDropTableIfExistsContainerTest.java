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
 * Oracle drops a table only if it exists, {@code DROP TABLE IF EXISTS}, from 23 (23ai) on. The dialect is built from a
 * live connection: from 23 on it writes IF EXISTS, the drop of a missing table passes and the table it drops is gone;
 * before, on 18 and 21, it writes a plain DROP TABLE, for the server refuses IF EXISTS with ORA-00933, and the drop of
 * a missing table fails with ORA-00942.
 */
@EnabledIfSystemProperty(named = "integration.docker", matches = "true")
class OracleDropTableIfExistsContainerTest {

    @ParameterizedTest(name = "{0} -> DROP TABLE IF EXISTS {1}")
    @CsvSource({ "gvenzl/oracle-xe:18-slim-faststart, false", "gvenzl/oracle-xe:21-slim-faststart, false",
            "gvenzl/oracle-free:23.9-slim-faststart, true",
            "gvenzl/oracle-free:23.26.4-slim-faststart, true" })
    void dropTableIfExists(String image, boolean expected) throws Exception {
        try (JdbcDatabaseContainer<?> ora = OracleContainers.of(image)) {
            ora.start();
            try (Connection c = DriverManager.getConnection(ora.getJdbcUrl(), ora.getUsername(), ora.getPassword());
                    Statement st = c.createStatement()) {
                String schema = ora.getUsername().toUpperCase();
                OracleDialect dialect = new OracleDialect(DialectInitData.fromConnection(c));

                assertThat(dialect.supportsDropTableIfExists()).as("supportsDropTableIfExists").isEqualTo(expected);

                st.execute("CREATE TABLE ITEMS (ID NUMBER)");
                String drop = dialect.dropTable(schema, "ITEMS", true);

                if (!expected) {
                    assertThat(drop).as("a plain drop before 23").isEqualTo("DROP TABLE \"" + schema + "\".\"ITEMS\"");
                    assertThatThrownBy(() -> st.execute("DROP TABLE IF EXISTS ITEMS"))
                            .as("IF EXISTS is refused before 23").isInstanceOf(SQLException.class)
                            .hasMessageContaining("ORA-00933");
                    st.execute(drop);
                    assertThat(exists(st, "ITEMS")).as("dropped").isFalse();
                    assertThatThrownBy(() -> st.execute(drop)).as("a missing one")
                            .isInstanceOf(SQLException.class).hasMessageContaining("ORA-00942");
                    return;
                }

                assertThat(drop).as("IF EXISTS from 23")
                        .isEqualTo("DROP TABLE IF EXISTS \"" + schema + "\".\"ITEMS\"");
                st.execute(drop);
                assertThat(exists(st, "ITEMS")).as("dropped").isFalse();
                st.execute(drop);
                assertThatThrownBy(() -> st.execute(dialect.dropTable(schema, "ITEMS", false)))
                        .as("a missing one without IF EXISTS").isInstanceOf(SQLException.class)
                        .hasMessageContaining("ORA-00942");
            }
        }
    }

    private static boolean exists(Statement st, String table) throws SQLException {
        try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM USER_TABLES WHERE TABLE_NAME = '" + table + "'")) {
            rs.next();
            return rs.getInt(1) > 0;
        }
    }
}
