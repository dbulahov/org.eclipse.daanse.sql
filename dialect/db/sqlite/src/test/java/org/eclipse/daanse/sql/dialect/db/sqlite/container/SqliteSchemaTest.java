/*
 * Copyright (c) 2026 Contributors to the Eclipse Foundation.
 *
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.daanse.sql.dialect.db.sqlite.container;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;

import org.eclipse.daanse.sql.dialect.api.generator.DdlGenerator;
import org.eclipse.daanse.sql.dialect.db.sqlite.SqliteDialect;
import org.eclipse.daanse.sql.model.schema.SchemaReference;
import org.eclipse.daanse.sql.model.schema.TableReference;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * SQLite keeps no schemas inside a database, in any version: {@code CREATE SCHEMA} and {@code DROP SCHEMA} are syntax
 * errors; its schemas are {@code main}, {@code temp} and the database files a connection attaches. SQLite is embedded:
 * the CLI of each version runs the statements in Docker, and the dialect is built for the version the CLI reports. The
 * dialect says it has no schemas and writes no statement to create or drop one; a table named with {@code main} or
 * {@code temp} is still one SQLite has.
 */
@EnabledIfSystemProperty(named = "integration.docker", matches = "true")
class SqliteSchemaTest {

    @ParameterizedTest(name = "sqlite {0}")
    @ValueSource(strings = { "3.22.0", "3.32.1", "3.46.1", "3.51.0" })
    void schemas(String version) {
        SqliteDialect d = SqliteCli.dialect(version);
        DdlGenerator ddl = d.ddlGenerator();
        assertThat(ddl.supportsSchemas()).as("supportsSchemas").isFalse();
        assertThat(ddl.createSchema("s", true)).as("no CREATE SCHEMA").isNull();
        assertThat(ddl.createSchema("s", false)).as("no CREATE SCHEMA").isNull();
        assertThat(ddl.dropSchema("s", true, true)).as("no DROP SCHEMA").isNull();
        assertThat(ddl.dropSchema("s", false, false)).as("no DROP SCHEMA").isNull();
        assertThat(d.supportsDropSchemaIfExists()).as("supportsDropSchemaIfExists").isFalse();

        for (String refused : new String[] { "CREATE SCHEMA \"s\";", "CREATE SCHEMA IF NOT EXISTS \"s\";",
                "DROP SCHEMA \"main\";", "DROP SCHEMA IF EXISTS \"s\";" }) {
            SqliteCli.Result r = SqliteCli.run(version, refused);
            assertThat(r.failed()).as("the server refuses " + refused).isTrue();
            assertThat(r.err()).as(refused).contains("syntax error");
        }

        TableReference inMain = new TableReference(Optional.of(new SchemaReference(Optional.empty(), "main")), "t",
                TableReference.TYPE_TABLE);
        TableReference inTemp = new TableReference(Optional.of(new SchemaReference(Optional.empty(), "temp")), "u",
                TableReference.TYPE_TABLE);
        String sql = "CREATE TABLE " + d.qualified(inMain) + "(a INTEGER); CREATE TABLE " + d.qualified(inTemp)
                + "(b INTEGER); INSERT INTO " + d.qualified(inMain) + " VALUES (1); INSERT INTO " + d.qualified(inTemp)
                + " VALUES (2); SELECT (SELECT a FROM main.t) || ':' || (SELECT b FROM temp.u);\n"
                + ddl.dropTable(inMain, true) + "; SELECT 'dropped';";
        SqliteCli.Result r = SqliteCli.run(version, sql);
        assertThat(r.failed()).as(r.err() + " in " + sql).isFalse();
        assertThat(r.out()).as("main and temp are schemas SQLite has").containsExactly("1:2", "dropped");
    }
}
