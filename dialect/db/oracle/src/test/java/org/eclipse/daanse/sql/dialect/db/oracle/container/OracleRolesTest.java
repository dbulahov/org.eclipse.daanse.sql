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
import java.util.Optional;

import org.eclipse.daanse.sql.dialect.api.DialectInitData;
import org.eclipse.daanse.sql.dialect.db.oracle.OracleDialect;
import org.eclipse.daanse.sql.model.schema.SchemaReference;
import org.eclipse.daanse.sql.model.schema.TableReference;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.testcontainers.containers.JdbcDatabaseContainer;

/**
 * Oracle has roles of old, and {@code CREATE ROLE IF NOT EXISTS} and {@code DROP ROLE IF EXISTS} from 23 (23ai) on.
 * The dialect is built from a live connection of SYSTEM: on every version the role it creates is there, holds a
 * privilege, is granted to a user, with the admin option too, and revoked, and is dropped with its grants; from 23 on
 * IF [NOT] EXISTS spares an error; before, on 18 and 21, it is left out, for the server refuses it, and a second create
 * fails with ORA-01921, a second drop with ORA-01919.
 */
@EnabledIfSystemProperty(named = "integration.docker", matches = "true")
class OracleRolesTest {

    private static final String ROLE = "RT_ROLE";
    private static final String USER = "RT_USER";
    private static final TableReference ITEMS = new TableReference(
            Optional.of(new SchemaReference(Optional.empty(), "SYSTEM")), "ITEMS", TableReference.TYPE_TABLE);

    @ParameterizedTest(name = "{0} -> IF [NOT] EXISTS {1}")
    @CsvSource({ "gvenzl/oracle-xe:18-slim-faststart, false", "gvenzl/oracle-xe:21-slim-faststart, false",
            "gvenzl/oracle-free:23.9-slim-faststart, true",
            "gvenzl/oracle-free:23.26.4-slim-faststart, true" })
    void roles(String image, boolean ifExists) throws Exception {
        try (JdbcDatabaseContainer<?> ora = OracleContainers.of(image)) {
            ora.start();
            // SYSTEM may create roles and users and read DBA_ROLES; the container's user may not
            try (Connection c = DriverManager.getConnection(ora.getJdbcUrl(), "system", ora.getPassword());
                    Statement st = c.createStatement()) {
                st.execute("CREATE TABLE ITEMS (ID NUMBER)");
                OracleDialect dialect = new OracleDialect(DialectInitData.fromConnection(c));

                assertThat(dialect.supportsRoles()).as("supportsRoles").isTrue();

                String create = dialect.createRole(ROLE, false).orElseThrow();
                assertThat(create).as("a plain create").isEqualTo("CREATE ROLE \"RT_ROLE\"");
                st.execute(create);
                assertThat(rows(st, "SELECT ROLE FROM DBA_ROLES WHERE ROLE = '" + ROLE + "'")).as("the role")
                        .containsExactly(ROLE);
                assertThatThrownBy(() -> st.execute(create)).as("a second one of the name")
                        .isInstanceOf(SQLException.class).hasMessageContaining("ORA-01921");

                String createIfNotExists = dialect.createRole(ROLE, true).orElseThrow();
                if (ifExists) {
                    assertThat(createIfNotExists).as("IF NOT EXISTS from 23")
                            .isEqualTo("CREATE ROLE IF NOT EXISTS \"RT_ROLE\"");
                    st.execute(createIfNotExists);
                } else {
                    assertThat(createIfNotExists).as("no IF NOT EXISTS before 23").isEqualTo(create);
                    assertThatThrownBy(() -> st.execute("CREATE ROLE IF NOT EXISTS " + ROLE))
                            .as("IF NOT EXISTS is refused before 23").isInstanceOf(SQLException.class);
                    assertThatThrownBy(() -> st.execute(createIfNotExists)).as("a second one, plainly")
                            .isInstanceOf(SQLException.class).hasMessageContaining("ORA-01921");
                }

                st.execute(dialect.grantTablePrivilege("SELECT", ITEMS, ROLE, false));
                assertThat(rows(st, "SELECT PRIVILEGE || ' ' || OWNER || '.' || TABLE_NAME FROM DBA_TAB_PRIVS"
                        + " WHERE GRANTEE = '" + ROLE + "'")).as("the role's privilege")
                        .containsExactly("SELECT SYSTEM.ITEMS");

                st.execute(dialect.createUser(USER, false).orElseThrow());
                st.execute(dialect.grantRole(ROLE, USER, false));
                assertThat(grants(st)).as("the role granted to the user").containsExactly(ROLE + ":NO");
                st.execute(dialect.revokeRole(ROLE, USER));
                assertThat(grants(st)).as("revoked").isEmpty();
                st.execute(dialect.grantRole(ROLE, USER, true));
                assertThat(grants(st)).as("with the admin option").containsExactly(ROLE + ":YES");

                String drop = dialect.dropRole(ROLE, false).orElseThrow();
                assertThat(drop).as("a plain drop").isEqualTo("DROP ROLE \"RT_ROLE\"");
                st.execute(drop);
                assertThat(rows(st, "SELECT ROLE FROM DBA_ROLES WHERE ROLE = '" + ROLE + "'")).as("dropped")
                        .isEmpty();
                assertThat(grants(st)).as("the grant gone with the role").isEmpty();
                assertThatThrownBy(() -> st.execute(drop)).as("a missing one").isInstanceOf(SQLException.class)
                        .hasMessageContaining("ORA-01919");

                String dropIfExists = dialect.dropRole(ROLE, true).orElseThrow();
                if (ifExists) {
                    assertThat(dropIfExists).as("IF EXISTS from 23").isEqualTo("DROP ROLE IF EXISTS \"RT_ROLE\"");
                    st.execute(dropIfExists);
                } else {
                    assertThat(dropIfExists).as("no IF EXISTS before 23").isEqualTo(drop);
                    assertThatThrownBy(() -> st.execute("DROP ROLE IF EXISTS " + ROLE))
                            .as("IF EXISTS is refused before 23").isInstanceOf(SQLException.class);
                    assertThatThrownBy(() -> st.execute(dropIfExists)).as("a missing one, plainly")
                            .isInstanceOf(SQLException.class).hasMessageContaining("ORA-01919");
                }
                st.execute(dialect.dropUser(USER, false).orElseThrow());
            }
        }
    }

    /** The roles granted to the user, each with whether it may grant it on. */
    private static List<String> grants(Statement st) throws SQLException {
        return rows(st, "SELECT GRANTED_ROLE || ':' || ADMIN_OPTION FROM DBA_ROLE_PRIVS WHERE GRANTEE = '" + USER
                + "'");
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
