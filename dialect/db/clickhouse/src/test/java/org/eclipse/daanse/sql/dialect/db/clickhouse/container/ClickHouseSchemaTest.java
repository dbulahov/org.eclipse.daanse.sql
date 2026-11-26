/*
 * Copyright (c) 2026 Contributors to the Eclipse Foundation.
 *
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.daanse.sql.dialect.db.clickhouse.container;

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
import org.eclipse.daanse.sql.dialect.db.clickhouse.ClickHouseDialect;
import org.eclipse.daanse.sql.model.schema.SchemaReference;
import org.eclipse.daanse.sql.model.schema.TableReference;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.clickhouse.ClickHouseContainer;

/**
 * A schema in ClickHouse is a database: {@code CREATE SCHEMA} and {@code DROP SCHEMA} are syntax errors in every
 * version. The dialect is built from a live connection and creates a schema with {@code CREATE DATABASE}, drops it with
 * {@code DROP DATABASE}, which takes its tables along, with no CASCADE: the schema is there, takes a table and its
 * rows, and is dropped with them; IF [NOT] EXISTS spares an error, and without it a second create and a second drop
 * fail.
 */
@EnabledIfSystemProperty(named = "integration.docker", matches = "true")
class ClickHouseSchemaTest {

    private static final TableReference T = new TableReference(
            Optional.of(new SchemaReference(Optional.empty(), "s")), "t", TableReference.TYPE_TABLE);

    private static final String DATABASES = "SELECT name FROM system.databases WHERE name = 's'";

    @ParameterizedTest(name = "clickhouse:{0}")
    @ValueSource(strings = { "21.3", "21.8", "23.8", "24.8", "25.8", "26.6" })
    void schemas(String tag) throws Exception {
        try (ClickHouseContainer ch = ClickHouseContainers.container(tag)) {
            ch.start();
            try (Connection c = DriverManager.getConnection(ClickHouseContainers.jdbcUrl(ch, tag), ch.getUsername(),
                    ch.getPassword()); Statement st = c.createStatement()) {
                ClickHouseDialect dialect = new ClickHouseDialect(DialectInitData.fromConnection(c));
                DdlGenerator ddl = dialect.ddlGenerator();
                assertThat(ddl.supportsSchemas()).as("supportsSchemas").isTrue();

                assertThatThrownBy(() -> st.execute("CREATE SCHEMA IF NOT EXISTS \"s\""))
                        .as("CREATE SCHEMA, a syntax error").isInstanceOf(SQLException.class);
                assertThatThrownBy(() -> st.execute("DROP SCHEMA IF EXISTS \"s\" CASCADE"))
                        .as("DROP SCHEMA, a syntax error").isInstanceOf(SQLException.class);

                String createIfNotExists = ddl.createSchema("s", true);
                assertThat(createIfNotExists).startsWith("CREATE DATABASE IF NOT EXISTS ");
                execute(st, createIfNotExists);
                execute(st, createIfNotExists);
                assertThat(rows(st, DATABASES)).as("the schema, a database").containsExactly("s");
                assertThatThrownBy(() -> st.execute(ddl.createSchema("s", false))).as("a second one of the name")
                        .isInstanceOf(SQLException.class);

                execute(st, "CREATE TABLE " + dialect.qualified(T) + " (a Int32) ENGINE = MergeTree() ORDER BY a");
                execute(st, "INSERT INTO " + dialect.qualified(T) + " VALUES (1)");
                assertThat(rows(st, "SELECT toString(count()) FROM " + dialect.qualified(T)))
                        .as("a table of the schema").containsExactly("1");

                String drop = ddl.dropSchema("s", false, true);
                assertThat(drop).as("no CASCADE, asked for or not").startsWith("DROP DATABASE ")
                        .doesNotContain("CASCADE");
                execute(st, drop);
                assertThat(rows(st, DATABASES)).as("dropped").isEmpty();
                assertThatThrownBy(() -> rows(st, "SELECT count() FROM " + dialect.qualified(T)))
                        .as("the table gone with it").isInstanceOf(SQLException.class);
                assertThatThrownBy(() -> st.execute(drop)).as("a missing one").isInstanceOf(SQLException.class);
                execute(st, ddl.dropSchema("s", true, true));
            }
        }
    }

    private static void execute(Statement st, String sql) throws SQLException {
        try {
            st.execute(sql);
        } catch (SQLException e) {
            throw new SQLException(e.getMessage() + " in " + sql, e);
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
