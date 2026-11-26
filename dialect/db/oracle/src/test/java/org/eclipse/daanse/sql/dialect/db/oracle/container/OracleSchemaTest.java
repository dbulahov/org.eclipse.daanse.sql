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
import org.eclipse.daanse.sql.dialect.api.generator.DdlGenerator;
import org.eclipse.daanse.sql.dialect.db.oracle.OracleDialect;
import org.eclipse.daanse.sql.model.schema.SchemaReference;
import org.eclipse.daanse.sql.model.schema.TableReference;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.testcontainers.containers.JdbcDatabaseContainer;

/**
 * A schema in Oracle is a user: {@code CREATE SCHEMA} is Oracle's {@code CREATE SCHEMA AUTHORIZATION}, objects for a
 * user there already (ORA-02420), and {@code DROP SCHEMA} no statement (ORA-00950). The dialect, built from a live
 * connection of SYSTEM, creates a schema as a user without credentials with a quota on USERS, from 18 on, and drops it
 * with CASCADE: the schema is there, takes a table and its rows, and is dropped with them; from 23 on IF [NOT] EXISTS
 * spares an error; on 18 and 21 it is left out, for the server refuses it, and a second create fails with ORA-01920.
 */
@EnabledIfSystemProperty(named = "integration.docker", matches = "true")
class OracleSchemaTest {

    private static final String SCHEMA = "RT_SCHEMA";
    private static final TableReference T = new TableReference(
            Optional.of(new SchemaReference(Optional.empty(), SCHEMA)), "T", TableReference.TYPE_TABLE);

    @ParameterizedTest(name = "{0} -> IF [NOT] EXISTS {1}")
    @CsvSource({ "gvenzl/oracle-xe:18-slim-faststart, false", "gvenzl/oracle-xe:21-slim-faststart, false",
            "gvenzl/oracle-free:23.9-slim-faststart, true",
            "gvenzl/oracle-free:23.26.4-slim-faststart, true" })
    void schemas(String image, boolean ifExists) throws Exception {
        try (JdbcDatabaseContainer<?> ora = OracleContainers.of(image)) {
            ora.start();
            // SYSTEM may create users and read DBA_USERS; the container's user may not
            try (Connection c = DriverManager.getConnection(ora.getJdbcUrl(), "system", ora.getPassword());
                    Statement st = c.createStatement()) {
                OracleDialect dialect = new OracleDialect(DialectInitData.fromConnection(c));
                DdlGenerator ddl = dialect.ddlGenerator();
                assertThat(ddl.supportsSchemas()).as("supportsSchemas from 18").isTrue();
                assertThat(dialect.supportsDropSchemaIfExists()).as("supportsDropSchemaIfExists").isEqualTo(ifExists);

                assertThatThrownBy(() -> st.execute("CREATE SCHEMA \"" + SCHEMA + "\""))
                        .as("CREATE SCHEMA is no schema's creation").isInstanceOf(SQLException.class)
                        .hasMessageContaining("ORA-02420");
                assertThatThrownBy(() -> st.execute("DROP SCHEMA \"" + SCHEMA + "\""))
                        .as("nor DROP SCHEMA a drop").isInstanceOf(SQLException.class)
                        .hasMessageContaining("ORA-00950");

                String create = ddl.createSchema(SCHEMA, false);
                assertThat(create).isEqualTo("CREATE USER \"RT_SCHEMA\" NO AUTHENTICATION QUOTA UNLIMITED ON USERS");
                st.execute(create);
                assertThat(rows(st, "SELECT AUTHENTICATION_TYPE FROM DBA_USERS WHERE USERNAME = '" + SCHEMA + "'"))
                        .as("the schema, a user authenticated by nothing").containsExactly("NONE");

                st.execute("CREATE TABLE " + dialect.qualified(T) + " (A NUMBER)");
                st.execute("INSERT INTO " + dialect.qualified(T) + " VALUES (1)");
                assertThat(rows(st, "SELECT TO_CHAR(COUNT(*)) FROM " + dialect.qualified(T)))
                        .as("a table of the schema takes rows: the quota").containsExactly("1");

                assertThatThrownBy(() -> st.execute(create)).as("a second one of the name")
                        .isInstanceOf(SQLException.class).hasMessageContaining("ORA-01920");
                String createIfNotExists = ddl.createSchema(SCHEMA, true);
                String dropIfExists = ddl.dropSchema(SCHEMA, true, false);
                if (ifExists) {
                    assertThat(createIfNotExists).as("IF NOT EXISTS from 23").startsWith("CREATE USER IF NOT EXISTS ");
                    st.execute(createIfNotExists);
                } else {
                    assertThat(createIfNotExists).as("no IF NOT EXISTS before 23").isEqualTo(create);
                    assertThat(dropIfExists).as("no IF EXISTS before 23").doesNotContain("IF EXISTS");
                }

                String drop = ddl.dropSchema(SCHEMA, false, false);
                assertThat(drop).as("CASCADE, asked for or not").isEqualTo("DROP USER \"RT_SCHEMA\" CASCADE");
                st.execute(drop);
                assertThat(rows(st, "SELECT USERNAME FROM DBA_USERS WHERE USERNAME = '" + SCHEMA + "'"))
                        .as("dropped, with its table").isEmpty();
                assertThatThrownBy(() -> rows(st, "SELECT COUNT(*) FROM " + dialect.qualified(T)))
                        .as("the table gone with it").isInstanceOf(SQLException.class)
                        .hasMessageContaining("ORA-00942");
                if (ifExists) {
                    assertThat(dropIfExists).as("IF EXISTS from 23")
                            .isEqualTo("DROP USER IF EXISTS \"RT_SCHEMA\" CASCADE");
                    st.execute(dropIfExists);
                } else {
                    assertThatThrownBy(() -> st.execute(drop)).as("a missing one").isInstanceOf(SQLException.class)
                            .hasMessageContaining("ORA-01918");
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
