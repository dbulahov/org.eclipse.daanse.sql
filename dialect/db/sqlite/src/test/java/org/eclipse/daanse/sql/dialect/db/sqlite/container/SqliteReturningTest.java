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

import org.eclipse.daanse.sql.dialect.api.generator.ReturningGenerator;
import org.eclipse.daanse.sql.dialect.db.sqlite.SqliteDialect;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * SQLite has {@code RETURNING} from 3.35.0, on INSERT, UPDATE and DELETE, an upsert too, and no {@code RETURNING …
 * INTO}. SQLite is embedded: the CLI of each version runs the statements in Docker, and the dialect is built for the
 * version the CLI reports. From 3.35 on the clause it writes must bring back the rows the statement wrote: the key
 * SQLite gave, a default it filled in, the values an UPDATE set, the row a DELETE took, every column with {@code *}, a
 * column named by a keyword; before, it writes none, for the server refuses it.
 */
@EnabledIfSystemProperty(named = "integration.docker", matches = "true")
class SqliteReturningTest {

    private static final String SETUP = "CREATE TABLE items(id INTEGER PRIMARY KEY, name TEXT UNIQUE,"
            + " qty INTEGER DEFAULT 1, \"order\" INTEGER);\n";

    @ParameterizedTest(name = "sqlite {0} -> RETURNING {1}")
    @CsvSource({ "3.22.0, false", "3.32.1, false", "3.35.5, true", "3.40.1, true", "3.46.1, true",
            "3.51.0, true" })
    void returning(String version, boolean expected) {
        SqliteDialect d = SqliteCli.dialect(version);
        ReturningGenerator g = d.returningGenerator();
        assertThat(g.supportsReturning()).as("supportsReturning").isEqualTo(expected);
        assertThat(g.supportsReturningInto()).as("no RETURNING … INTO").isFalse();
        assertThat(g.returningInto(List.of("id"))).isEmpty();
        assertThat(g.returning(List.of())).as("no columns, no clause").isEmpty();

        if (!expected) {
            assertThat(g.returning(List.of("id"))).as("none before 3.35").isEmpty();
            SqliteCli.Result refused = SqliteCli.run(version,
                    SETUP + "INSERT INTO items(name) VALUES ('a') RETURNING id;");
            assertThat(refused.failed()).as("the server refuses RETURNING before 3.35").isTrue();
            assertThat(refused.err()).contains("syntax error");
            return;
        }

        String sql = SETUP
                + "INSERT INTO items(name) VALUES ('a'), ('b')" + clause(g, "id", "name", "qty") + ";\n"
                + "UPDATE items SET qty = qty + 5, \"order\" = 7 WHERE name = 'a'" + clause(g, "id", "qty", "order")
                + ";\n"
                + "DELETE FROM items WHERE id = 2" + clause(g, "*") + ";\n"
                + "INSERT INTO items(name, qty) VALUES ('a', 9) ON CONFLICT(name) DO UPDATE SET qty = excluded.qty"
                + clause(g, "id", "qty") + ";\n";
        SqliteCli.Result r = SqliteCli.run(version, sql);
        assertThat(r.failed()).as(r.err() + " in " + sql).isFalse();
        assertThat(r.out()).as("the rows each statement wrote").containsExactly(
                "1|a|1", "2|b|1", // the keys SQLite gave, the default it filled in
                "1|6|7", // the values the UPDATE set, a column named by a keyword
                "2|b|1|", // the row the DELETE took, every column
                "1|9"); // the upsert's row
        assertThat(g.returning(List.of("*")).orElseThrow()).isEqualTo(" RETURNING *");
        assertThat(g.returning(List.of("order")).orElseThrow()).as("a keyword quoted")
                .isEqualTo(" RETURNING " + d.quoteIdentifier("order"));
    }

    private static String clause(ReturningGenerator g, String... columns) {
        return g.returning(List.of(columns)).orElseThrow();
    }
}
