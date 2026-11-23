/*
 * Copyright (c) 2026 Contributors to the Eclipse Foundation.
 *
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.daanse.sql.dialect.db.postgresql.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.eclipse.daanse.sql.dialect.api.DialectInitData;
import org.eclipse.daanse.sql.dialect.api.generator.MergeGenerator;
import org.eclipse.daanse.sql.dialect.api.generator.MergeGenerator.UpsertSpec;
import org.eclipse.daanse.sql.dialect.api.generator.ReturningGenerator;
import org.eclipse.daanse.sql.dialect.db.postgresql.PostgreSqlDialect;
import org.eclipse.daanse.sql.model.schema.SchemaReference;
import org.eclipse.daanse.sql.model.schema.TableReference;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * PostgreSQL returns the rows a statement wrote with {@code RETURNING}, since 8.2, so on every version the registry
 * still serves. The dialect is built from a live connection, and the clause it writes must bring back what an
 * {@code INSERT}, an {@code UPDATE} and a {@code DELETE} wrote: a key the database made, a default, a quoted column of
 * mixed case, every column; and from 9.5 on, with the dialect's upsert, the row it inserted or updated, and none where
 * it did nothing.
 */
@EnabledIfSystemProperty(named = "integration.docker", matches = "true")
class PostgreSqlReturningTest {

    private static final TableReference ITEMS = new TableReference(
            Optional.of(new SchemaReference(Optional.empty(), "public")), "items", TableReference.TYPE_TABLE);

    /** A key the database makes, and a column of mixed case, which only a quoted name reaches, with a default. */
    private static final String SETUP = """
            CREATE TABLE items(id SERIAL PRIMARY KEY, name VARCHAR(20), "Note" VARCHAR(20) DEFAULT 'n/a');
            """;

    @ParameterizedTest(name = "postgres:{0} -> upsert {1}")
    @CsvSource({ "9.1, false", "9.5, true", "16-alpine, true" })
    void returning(String tag, boolean upsert) throws Exception {
        try (PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:" + tag)) {
            pg.start();
            try (Connection c = pg.createConnection(""); Statement st = c.createStatement()) {
                st.execute(SETUP);
                PostgreSqlDialect dialect = new PostgreSqlDialect(DialectInitData.fromConnection(c));
                ReturningGenerator ret = dialect.returningGenerator();

                assertThat(ret.supportsReturning()).isTrue();
                assertThat(ret.returning(List.of())).as("no columns, no clause").isEmpty();
                assertThat(ret.returning(null)).as("no columns, no clause").isEmpty();

                assertThat(rows(st, "INSERT INTO items(name) VALUES ('apple')" + clause(ret, "id")))
                        .as("the key the database made").containsExactly("1");
                assertThat(rows(st, "INSERT INTO items(name) VALUES ('pear'), ('plum')" + clause(ret, "id", "name")))
                        .as("two rows, two columns").containsExactly("2|pear", "3|plum");
                assertThat(rows(st, "INSERT INTO items(name) VALUES ('fig')" + clause(ret, "Note")))
                        .as("a quoted column of mixed case, its default").containsExactly("n/a");
                assertThat(rows(st, "INSERT INTO items(name, \"Note\") VALUES ('kiwi', 'green')" + clause(ret, "*")))
                        .as("every column").containsExactly("5|kiwi|green");

                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO items(name) VALUES (?)" + clause(ret, "id", "name"))) {
                    ps.setString(1, "lime");
                    assertThat(rows(ps)).as("prepared, with a parameter").containsExactly("6|lime");
                }

                assertThat(rows(st, "UPDATE items SET \"Note\" = 'sold' WHERE id IN (1, 2)"
                        + clause(ret, "id", "Note")))
                        .as("UPDATE, the new values").containsExactlyInAnyOrder("1|sold", "2|sold");
                assertThat(rows(st, "DELETE FROM items WHERE name LIKE 'p%'" + clause(ret, "name")))
                        .as("DELETE, the rows it removed").containsExactlyInAnyOrder("pear", "plum");
                assertThat(rows(st, "DELETE FROM items WHERE id = 99" + clause(ret, "id")))
                        .as("nothing deleted, no row").isEmpty();

                MergeGenerator merge = dialect.mergeGenerator();
                assertThat(merge.supportsMerge()).isEqualTo(upsert);
                if (upsert) {
                    UpsertSpec update = new UpsertSpec(ITEMS, List.of("id"), List.of("id", "name"), List.of("name"));
                    UpsertSpec ignore = new UpsertSpec(ITEMS, List.of("id"), List.of("id", "name"), List.of());

                    assertThat(rows(st, merge.upsert(update, List.of("1", "'apricot'")).orElseThrow()
                            + clause(ret, "id", "name"))).as("upsert of an existing key, the updated row")
                            .containsExactly("1|apricot");
                    assertThat(rows(st, merge.upsert(update, List.of("50", "'mango'")).orElseThrow()
                            + clause(ret, "id", "name"))).as("upsert of a new key, the inserted row")
                            .containsExactly("50|mango");
                    assertThat(rows(st, merge.upsert(ignore, List.of("50", "'melon'")).orElseThrow()
                            + clause(ret, "id", "name"))).as("DO NOTHING on an existing key, no row").isEmpty();
                }
            }
        }
    }

    private static String clause(ReturningGenerator ret, String... columns) {
        return ret.returning(List.of(columns)).orElseThrow();
    }

    private static List<String> rows(Statement st, String sql) throws SQLException {
        try (ResultSet rs = st.executeQuery(sql)) {
            return rows(rs);
        }
    }

    private static List<String> rows(PreparedStatement ps) throws SQLException {
        try (ResultSet rs = ps.executeQuery()) {
            return rows(rs);
        }
    }

    private static List<String> rows(ResultSet rs) throws SQLException {
        List<String> out = new ArrayList<>();
        int n = rs.getMetaData().getColumnCount();
        while (rs.next()) {
            StringBuilder row = new StringBuilder();
            for (int i = 1; i <= n; i++) {
                if (i > 1)
                    row.append('|');
                row.append(rs.getString(i));
            }
            out.add(row.toString());
        }
        return out;
    }
}
