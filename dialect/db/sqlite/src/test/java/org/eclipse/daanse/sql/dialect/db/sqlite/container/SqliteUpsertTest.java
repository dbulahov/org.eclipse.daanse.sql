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

import org.eclipse.daanse.sql.dialect.api.generator.MergeGenerator;
import org.eclipse.daanse.sql.dialect.api.generator.MergeGenerator.UpsertSpec;
import org.eclipse.daanse.sql.dialect.db.sqlite.SqliteDialect;
import org.eclipse.daanse.sql.model.schema.TableReference;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * SQLite has the upsert, {@code INSERT … ON CONFLICT (key) DO UPDATE | DO NOTHING}, from 3.24.0. SQLite is embedded:
 * the CLI of each version runs the statements in Docker, and the dialect is built for the version the CLI reports.
 * From 3.24 on the upsert it writes must insert a row of a new key, update in place the listed columns of a row of a
 * key there, its other columns and its rowid kept, of a key of two columns too; and with no column to update leave the
 * row there as it is. Before, on 3.22, it writes none, for the server refuses it; INSERT OR REPLACE, which it has,
 * would lose the columns not listed.
 */
@EnabledIfSystemProperty(named = "integration.docker", matches = "true")
class SqliteUpsertTest {

    private static final TableReference ITEMS = new TableReference(Optional.empty(), "items",
            TableReference.TYPE_TABLE);
    private static final TableReference STOCK = new TableReference(Optional.empty(), "stock",
            TableReference.TYPE_TABLE);

    private static final String SETUP = """
            CREATE TABLE items(id INTEGER PRIMARY KEY, name TEXT, qty INTEGER, note TEXT DEFAULT 'none');
            INSERT INTO items VALUES (1, 'a', 1, 'kept');
            CREATE TABLE stock(store TEXT, item INTEGER, qty INTEGER, PRIMARY KEY (store, item));
            INSERT INTO stock VALUES ('x', 1, 5);
            """;

    private static final UpsertSpec UPDATE = new UpsertSpec(ITEMS, List.of("id"), List.of("id", "name", "qty"),
            List.of("name", "qty"));
    private static final UpsertSpec IGNORE = new UpsertSpec(ITEMS, List.of("id"), List.of("id", "name", "qty"),
            List.of());
    private static final UpsertSpec COMPOSITE = new UpsertSpec(STOCK, List.of("store", "item"),
            List.of("store", "item", "qty"), List.of("qty"));

    @ParameterizedTest(name = "sqlite {0} -> upsert {1}")
    @CsvSource({ "3.22.0, false", "3.25.3, true", "3.32.1, true", "3.35.5, true", "3.46.1, true", "3.51.0, true" })
    void upsert(String version, boolean expected) {
        SqliteDialect d = SqliteCli.dialect(version);
        MergeGenerator g = d.mergeGenerator();
        assertThat(g.supportsMerge()).as("supportsMerge").isEqualTo(expected);

        if (!expected) {
            assertThat(g.upsert(UPDATE, List.of("1", "'b'", "2"))).as("no upsert before 3.24").isEmpty();
            SqliteCli.Result refused = SqliteCli.run(version, SETUP
                    + "INSERT INTO items (id, name, qty) VALUES (1, 'b', 2) ON CONFLICT (id) DO NOTHING;");
            assertThat(refused.failed()).as("the server refuses ON CONFLICT after INSERT before 3.24").isTrue();
            assertThat(refused.err()).contains("syntax error");
            SqliteCli.Result replace = SqliteCli.run(version, SETUP
                    + "INSERT OR REPLACE INTO items (id, name, qty) VALUES (1, 'b', 2);"
                    + " SELECT id || '|' || name || '|' || qty || '|' || note FROM items;");
            assertThat(replace.out()).as("INSERT OR REPLACE is no stand-in: the note not listed is lost")
                    .containsExactly("1|b|2|none");
            return;
        }

        assertThat(g.upsert(IGNORE, List.of("1", "'b'", "2")).orElseThrow()).as("insert-or-ignore")
                .endsWith(" DO NOTHING");
        String sql = SETUP
                + g.upsert(UPDATE, List.of("2", "'n'", "7")).orElseThrow() + ";\n"
                + g.upsert(UPDATE, List.of("1", "'b'", "2")).orElseThrow() + ";\n"
                + g.upsert(IGNORE, List.of("2", "'ignored'", "99")).orElseThrow() + ";\n"
                + g.upsert(IGNORE, List.of("3", "'c'", "3")).orElseThrow() + ";\n"
                + g.upsert(COMPOSITE, List.of("'x'", "1", "8")).orElseThrow() + ";\n"
                + g.upsert(COMPOSITE, List.of("'y'", "1", "4")).orElseThrow() + ";\n"
                + "SELECT 'items|' || group_concat(r, ',') FROM (SELECT rowid || ':' || id || ':' || name || ':' || qty"
                + " || ':' || note AS r FROM items ORDER BY id);\n"
                + "SELECT 'stock|' || group_concat(r, ',') FROM (SELECT store || ':' || item || ':' || qty AS r"
                + " FROM stock ORDER BY store, item);\n";
        SqliteCli.Result r = SqliteCli.run(version, sql);
        assertThat(r.failed()).as(r.err() + " in " + sql).isFalse();
        assertThat(r.out()).containsExactly(
                // 1 updated in place, its note and rowid kept; 2 inserted, then left by DO NOTHING; 3 inserted
                "items|1:1:b:2:kept,2:2:n:7:none,3:3:c:3:none",
                // a key of two columns: x/1 updated, y/1 inserted
                "stock|x:1:8,y:1:4");
    }
}
