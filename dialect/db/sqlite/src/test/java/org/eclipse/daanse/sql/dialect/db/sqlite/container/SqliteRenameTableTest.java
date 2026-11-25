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

import java.util.List;
import java.util.Optional;

import org.eclipse.daanse.sql.dialect.api.generator.DdlGenerator;
import org.eclipse.daanse.sql.dialect.api.generator.DdlGenerator.TableRename;
import org.eclipse.daanse.sql.dialect.db.sqlite.SqliteDialect;
import org.eclipse.daanse.sql.model.schema.SchemaReference;
import org.eclipse.daanse.sql.model.schema.TableReference;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * SQLite renames a table with {@code ALTER TABLE t RENAME TO u} in every version, the new name by itself. SQLite is
 * embedded: the CLI of each version runs the statements in Docker, and the dialect is built for the version the CLI
 * reports. The table the dialect renames, named with its schema, keeps its rows, and the foreign keys of other tables
 * follow it in every version; its views and triggers follow it from 3.25.0 on, before they are left naming the old
 * table and fail. Several renames are one statement each, which swap two tables through a third name; a view SQLite
 * renames in no version, and the dialect writes no rename of one.
 */
@EnabledIfSystemProperty(named = "integration.docker", matches = "true")
class SqliteRenameTableTest {

    private static final Optional<SchemaReference> MAIN = Optional.of(new SchemaReference(Optional.empty(), "main"));

    private static final String SETUP = """
            PRAGMA foreign_keys = ON;
            CREATE TABLE t(id INTEGER PRIMARY KEY, a INTEGER); INSERT INTO t VALUES (1, 10);
            CREATE TABLE c(tid INTEGER REFERENCES t(id)); INSERT INTO c VALUES (1);
            CREATE VIEW v AS SELECT a FROM t;
            CREATE TABLE log(x INTEGER);
            CREATE TRIGGER tr AFTER INSERT ON c BEGIN INSERT INTO log SELECT a FROM t; END;
            CREATE TABLE x(n TEXT); INSERT INTO x VALUES ('x'); CREATE TABLE y(n TEXT); INSERT INTO y VALUES ('y');
            """;

    @ParameterizedTest(name = "sqlite {0} -> views and triggers follow {1}")
    @CsvSource({ "3.22.0, false", "3.25.3, true", "3.32.1, true", "3.46.1, true", "3.51.0, true" })
    void rename_table(String version, boolean dependentsFollow) {
        SqliteDialect d = SqliteCli.dialect(version);
        DdlGenerator ddl = d.ddlGenerator();
        assertThat(ddl.supportsRenameTable()).as("supportsRenameTable").isTrue();
        assertThat(ddl.supportsRenameView()).as("supportsRenameView").isFalse();
        assertThat(ddl.supportsAtomicMultiRenameTable()).as("supportsAtomicMultiRenameTable").isFalse();
        assertThat(ddl.renameView(table("v", TableReference.TYPE_VIEW), "w")).as("no rename of a view").isNull();

        String rename = ddl.renameTable(table("t", TableReference.TYPE_TABLE), "u");
        assertThat(rename).as("the new name by itself").endsWith(" RENAME TO " + d.quoteIdentifier("u"));
        List<String> swap = ddl.renameTables(List.of(new TableRename(table("x", TableReference.TYPE_TABLE), "tmp"),
                new TableRename(table("y", TableReference.TYPE_TABLE), "x"),
                new TableRename(table("tmp", TableReference.TYPE_TABLE), "y")));
        assertThat(swap).as("one statement per rename").hasSize(3);

        StringBuilder sql = new StringBuilder(SETUP).append(rename).append(";\n");
        swap.forEach(s -> sql.append(s).append(";\n"));
        sql.append("SELECT 'rows|' || (SELECT id || ':' || a FROM u);\n")
                .append("SELECT 'fk|' || (instr((SELECT sql FROM sqlite_master WHERE name = 'c'), '\"u\"') > 0);\n")
                .append("SELECT 'view sql|' || (instr((SELECT sql FROM sqlite_master WHERE name = 'v'), '\"u\"')"
                        + " > 0);\n")
                .append("SELECT 'swap|' || (SELECT n FROM x) || (SELECT n FROM y);\n");
        if (dependentsFollow) {
            sql.append("SELECT 'view|' || (SELECT a FROM v);\n")
                    .append("INSERT INTO c VALUES (1); SELECT 'trigger|' || count(*) FROM log;\n");
        }
        SqliteCli.Result r = SqliteCli.run(version, sql.toString());
        assertThat(r.failed()).as(r.err() + " in " + sql).isFalse();
        List<String> expected = dependentsFollow
                ? List.of("rows|1:10", "fk|1", "view sql|1", "swap|yx", "view|10", "trigger|1")
                : List.of("rows|1:10", "fk|1", "view sql|0", "swap|yx");
        assertThat(r.out()).as("the rows kept, the foreign key follows, the tables swapped")
                .containsExactlyElementsOf(expected);

        if (!dependentsFollow) {
            SqliteCli.Result view = SqliteCli.run(version, SETUP + rename + "; SELECT a FROM v;");
            assertThat(view.failed()).as("the view names the old table before 3.25").isTrue();
            assertThat(view.err()).contains("no such table");
            SqliteCli.Result trigger = SqliteCli.run(version, SETUP + rename + "; INSERT INTO c VALUES (1);");
            assertThat(trigger.failed()).as("so does the trigger").isTrue();
            assertThat(trigger.err()).contains("no such table");
        }

        SqliteCli.Result qualified = SqliteCli.run(version, SETUP + "ALTER TABLE t RENAME TO \"main\".\"u\";");
        assertThat(qualified.failed()).as("a new name with its schema is refused").isTrue();
        SqliteCli.Result view = SqliteCli.run(version, SETUP + "ALTER TABLE v RENAME TO w;");
        assertThat(view.failed()).as("a view is not renamed").isTrue();
        assertThat(view.err()).contains("may not be altered");
    }

    private static TableReference table(String name, String type) {
        return new TableReference(MAIN, name, type);
    }
}
