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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.eclipse.daanse.sql.dialect.db.sqlite.SqliteDialect;
import org.eclipse.daanse.sql.model.schema.TableReference;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * SQLite renames a column with {@code ALTER TABLE … RENAME COLUMN} from 3.25.0, which changes the name only, and
 * renames it in the table's indexes and views as well. SQLite is embedded and serves no connection: the CLI of each
 * version runs the statements in Docker, and the dialect is built for the version the CLI reports. From 3.25 on the
 * column it renames, the primary key too, keeps its type, its NOT NULL, its default and its values, an index and a view
 * follow it; before, on 3.22, it writes no rename, for the server refuses it.
 */
@EnabledIfSystemProperty(named = "integration.docker", matches = "true")
class SqliteRenameColumnTest {

    private static final TableReference T = new TableReference(Optional.empty(), "t", TableReference.TYPE_TABLE);

    private static final String SETUP = """
            CREATE TABLE t(id INTEGER PRIMARY KEY, note TEXT NOT NULL DEFAULT 'n/a', price REAL);
            INSERT INTO t(id, note, price) VALUES (1, 'x', 1.5);
            INSERT INTO t(id, price) VALUES (2, NULL);
            CREATE INDEX t_note ON t(note);
            CREATE VIEW v AS SELECT note FROM t;
            """;

    @ParameterizedTest(name = "sqlite {0} -> RENAME COLUMN {1}")
    @CsvSource({ "3.22.0, false", "3.25.3, true", "3.32.1, true", "3.40.1, true", "3.46.1, true",
            "3.51.0, true" })
    void rename_column(String version, boolean renameColumn) {
        SqliteDialect dialect = SqliteCli.dialect(version);
        assertThat(dialect.supportsRenameColumn()).as("supportsRenameColumn").isEqualTo(renameColumn);

        if (!renameColumn) {
            assertThat(dialect.renameColumn(T, "note", "remark")).as("no rename before 3.25").isNull();
            SqliteCli.Result r = SqliteCli.run(version, SETUP + "ALTER TABLE t RENAME COLUMN note TO remark;");
            assertThat(r.failed()).as("the server refuses it").isTrue();
            assertThat(r.err()).contains("syntax error");
            return;
        }

        List<String> renames = List.of(dialect.renameColumn(T, "note", "remark"),
                dialect.renameColumn(T, "price", "cost"), dialect.renameColumn(T, "id", "key_id"));
        assertThat(renames).allSatisfy(sql -> assertThat(sql).contains("RENAME COLUMN"));

        Map<String, String> checks = new LinkedHashMap<>();
        checks.put("columns", "SELECT group_concat(name || ':' || type || ':' || \"notnull\" || ':'"
                + " || ifnull(dflt_value, '-') || ':' || pk, ',') FROM pragma_table_info('t')");
        checks.put("values", "SELECT group_concat(key_id || ':' || remark || ':' || ifnull(cost, '-'), ',')"
                + " FROM (SELECT * FROM t ORDER BY key_id)");
        checks.put("index", "SELECT group_concat(name, ',') FROM pragma_index_info('t_note')");
        checks.put("view", "SELECT (SELECT count(*) FROM v) || ':' || (instr((SELECT sql FROM sqlite_master"
                + " WHERE name = 'v'), 'remark') > 0)");
        checks.put("default", "SELECT remark FROM t WHERE key_id = 3");

        StringBuilder sql = new StringBuilder(SETUP);
        renames.forEach(r -> sql.append(r).append(";\n"));
        sql.append("INSERT INTO t(key_id) VALUES (3);\n");
        checks.forEach((label, query) -> sql.append("SELECT '").append(label).append("|' || (").append(query)
                .append(");\n"));
        SqliteCli.Result r = SqliteCli.run(version, sql.toString());
        assertThat(r.failed()).as("every rename and check runs: " + r.err() + " in " + sql).isFalse();
        Map<String, String> got = new LinkedHashMap<>();
        for (String line : r.out()) {
            int bar = line.indexOf('|');
            if (bar > 0) {
                got.put(line.substring(0, bar), line.substring(bar + 1));
            }
        }

        assertThat(got.get("columns")).as("each column renamed, and kept whole")
                .isEqualTo("key_id:INTEGER:0:-:1,remark:TEXT:1:'n/a':0,cost:REAL:0:-:0");
        assertThat(got.get("values")).as("the values kept").isEqualTo("1:x:1.5,2:n/a:-,3:n/a:-");
        assertThat(got.get("index")).as("the index follows the column").isEqualTo("remark");
        assertThat(got.get("view")).as("the view follows the column, and reads").isEqualTo("3:1");
        assertThat(got.get("default")).as("the default kept").isEqualTo("n/a");
    }
}
