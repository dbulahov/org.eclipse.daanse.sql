/*
 * Copyright (c) 2026 Contributors to the Eclipse Foundation.
 *
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.daanse.sql.dialect.db.duckdb.container;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;

import org.eclipse.daanse.sql.dialect.api.generator.DdlGenerator;
import org.eclipse.daanse.sql.dialect.db.duckdb.DuckDbDialect;
import org.eclipse.daanse.sql.model.schema.SchemaReference;
import org.eclipse.daanse.sql.model.schema.TableReference;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * DuckDB renames a table ({@code ALTER TABLE … RENAME TO}) and a column ({@code ALTER TABLE … RENAME COLUMN}), and
 * renames neither an index nor a constraint, in every version from 1.0 on: up to 1.5 it says "Not implemented", from 2.0
 * it reads no such statement. The CLI of each version runs what the dialect writes, in Docker, and the dialect is
 * built for the version the CLI reports: a table and a column it renames keep their rows under the new name, and the
 * old name is gone; a table with an index, or a column of one, is refused with a "Dependency Error", as the dialect
 * says; an index and a constraint it renames not, and the server refuses the statement.
 */
@EnabledIfSystemProperty(named = "integration.docker", matches = "true")
class DuckDbRenameTest {

    private static final String SETUP = "CREATE TABLE emp(id INT PRIMARY KEY, name VARCHAR,"
            + " CONSTRAINT ck_id CHECK (id > 0)); INSERT INTO emp VALUES (1, 'ann'), (2, 'bob');";

    private static final TableReference EMP = new TableReference(
            Optional.of(new SchemaReference(Optional.empty(), "main")), "emp", TableReference.TYPE_TABLE);

    @ParameterizedTest(name = "duckdb:{0}")
    @ValueSource(strings = { "1.0.0", "1.1.3", "1.2.2", "1.3.2", "1.4.4", "1.5.6", "2.0.1" })
    void rename(String tag) {
        DuckDbDialect dialect = DuckDbCli.dialect(tag);
        DdlGenerator ddl = dialect.ddlGenerator();

        assertThat(ddl.supportsRenameTable()).as("supportsRenameTable").isTrue();
        assertThat(ddl.supportsRenameColumn()).as("supportsRenameColumn").isTrue();
        assertThat(ddl.supportsRenameIndex()).as("supportsRenameIndex").isFalse();
        assertThat(ddl.supportsRenameConstraint()).as("supportsRenameConstraint").isFalse();

        // a table and a column, renamed, keep their rows; the old names are gone
        String renameColumn = ddl.renameColumn(EMP, "name", "full_name");
        assertThat(renameColumn).isEqualTo("ALTER TABLE \"main\".\"emp\" RENAME COLUMN \"name\" TO \"full_name\"");
        String renameTable = ddl.renameTable(EMP, "staff");
        assertThat(renameTable).isEqualTo("ALTER TABLE \"main\".\"emp\" RENAME TO \"staff\"");
        DuckDbCli.Result renamed = DuckDbCli.run(tag, SETUP + " " + renameColumn + "; " + renameTable + ";"
                + " SELECT id || '=' || full_name FROM main.staff ORDER BY id;"
                + " SELECT 'tables ' || string_agg(table_name, ',' ORDER BY table_name)"
                + " FROM information_schema.tables WHERE table_schema = 'main';"
                + " SELECT 'columns ' || string_agg(column_name, ',' ORDER BY ordinal_position)"
                + " FROM information_schema.columns WHERE table_name = 'staff';");
        assertThat(renamed.failed()).as(renamed.err()).isFalse();
        assertThat(renamed.out()).as("the rows under the new names, the old names gone")
                .containsExactly("1=ann", "2=bob", "tables staff", "columns id,full_name");

        // a table with an index, and a column of it, are refused: the dependency the dialect's javadoc names
        String index = " CREATE INDEX ix_name ON emp(name);";
        assertRefused(tag, SETUP + index + " " + renameTable + ";", "Dependency Error", "a table with an index");
        assertRefused(tag, SETUP + index + " " + ddl.renameColumn(EMP, "name", "nm") + ";", "Dependency Error",
                "a column with an index");

        // an index and a constraint: not written, and the server refuses the statement
        assertThat(ddl.renameIndex("ix_name", "ix_full_name", EMP)).as("no index rename").isNull();
        assertThat(ddl.renameConstraint(EMP, "ck_id", "ck_positive")).as("no constraint rename").isNull();
        assertRefused(tag, SETUP + index + " ALTER INDEX ix_name RENAME TO ix_full_name;", "Error",
                "ALTER INDEX … RENAME");
        assertRefused(tag, SETUP + " ALTER TABLE emp RENAME CONSTRAINT ck_id TO ck_positive;", "Error",
                "RENAME CONSTRAINT");
    }

    private static void assertRefused(String tag, String sql, String error, String what) {
        DuckDbCli.Result r = DuckDbCli.run(tag, sql);
        assertThat(r.failed()).as(what + " is refused").isTrue();
        assertThat(r.err()).as(what).contains(error);
    }
}
