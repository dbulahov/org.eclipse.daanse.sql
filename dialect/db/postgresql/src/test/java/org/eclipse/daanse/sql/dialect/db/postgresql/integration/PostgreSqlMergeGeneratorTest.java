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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.eclipse.daanse.sql.dialect.api.DialectInitData;
import org.eclipse.daanse.sql.dialect.api.generator.MergeGenerator;
import org.eclipse.daanse.sql.dialect.api.generator.MergeGenerator.UpsertSpec;
import org.eclipse.daanse.sql.dialect.db.postgresql.PostgreSqlDialect;
import org.eclipse.daanse.sql.model.schema.SchemaReference;
import org.eclipse.daanse.sql.model.schema.TableReference;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * PostgreSQL writes an upsert as {@code INSERT … ON CONFLICT}, which came with 9.5. The dialect is built from a live
 * connection: from 9.5 on its upsert inserts a new key, updates or leaves an existing one; on 9.4 it has no upsert, and
 * the server would refuse {@code ON CONFLICT}.
 */
@EnabledIfSystemProperty(named = "integration.docker", matches = "true")
class PostgreSqlMergeGeneratorTest {

    private static final SchemaReference PUBLIC = new SchemaReference(Optional.empty(), "public");
    private static final TableReference PRODUCTS = new TableReference(Optional.of(PUBLIC), "products",
            TableReference.TYPE_TABLE);
    private static final TableReference STOCK = new TableReference(Optional.of(PUBLIC), "stock",
            TableReference.TYPE_TABLE);

    private static final String SETUP = """
            CREATE TABLE products(id INT PRIMARY KEY, name VARCHAR(20), price INT);
            CREATE TABLE stock(store VARCHAR(10), product INT, qty INT, PRIMARY KEY (store, product));
            INSERT INTO products VALUES (1, 'apple', 10);
            INSERT INTO stock VALUES ('north', 1, 5);
            """;

    private static final UpsertSpec UPDATE_PRODUCT = new UpsertSpec(PRODUCTS, List.of("id"),
            List.of("id", "name", "price"), List.of("name", "price"));
    private static final UpsertSpec IGNORE_PRODUCT = new UpsertSpec(PRODUCTS, List.of("id"),
            List.of("id", "name", "price"), List.of());
    private static final UpsertSpec UPDATE_STOCK = new UpsertSpec(STOCK, List.of("store", "product"),
            List.of("store", "product", "qty"), List.of("qty"));

    @ParameterizedTest(name = "postgres:{0} -> {1}")
    @CsvSource({ "9.4, false", "9.5, true", "16-alpine, true" })
    void upsert(String tag, boolean expected) throws Exception {
        try (PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:" + tag)) {
            pg.start();
            try (Connection c = pg.createConnection(""); Statement st = c.createStatement()) {
                st.execute(SETUP);
                MergeGenerator merge = new PostgreSqlDialect(DialectInitData.fromConnection(c)).mergeGenerator();

                assertThat(merge.supportsMerge()).isEqualTo(expected);

                if (!expected) {
                    assertThat(merge.upsert(UPDATE_PRODUCT, List.of("1", "'pear'", "20"))).isEmpty();
                    assertThatThrownBy(() -> st.execute(
                            "INSERT INTO products VALUES (1, 'pear', 20) ON CONFLICT (id) DO NOTHING"))
                            .as("PostgreSQL 9.4 knows no ON CONFLICT").isInstanceOf(SQLException.class)
                            .hasMessageContaining("syntax error");
                    return;
                }

                st.execute(sql(merge, UPDATE_PRODUCT, "2", "'banana'", "7"));
                assertThat(rows(st, "SELECT id, name, price FROM products ORDER BY id"))
                        .as("a new key is inserted").containsExactly("1|apple|10", "2|banana|7");

                st.execute(sql(merge, UPDATE_PRODUCT, "1", "'apple'", "12"));
                assertThat(rows(st, "SELECT id, name, price FROM products ORDER BY id"))
                        .as("an existing key is updated").containsExactly("1|apple|12", "2|banana|7");

                String ignore = sql(merge, IGNORE_PRODUCT, "1", "'cherry'", "99");
                assertThat(ignore).endsWith("DO NOTHING");
                st.execute(ignore);
                assertThat(rows(st, "SELECT id, name, price FROM products ORDER BY id"))
                        .as("without update columns an existing key is left").containsExactly("1|apple|12", "2|banana|7");

                st.execute(sql(merge, UPDATE_STOCK, "'north'", "1", "8"));
                st.execute(sql(merge, UPDATE_STOCK, "'south'", "1", "3"));
                assertThat(rows(st, "SELECT store, product, qty FROM stock ORDER BY store"))
                        .as("a composite key").containsExactly("north|1|8", "south|1|3");

                assertThatThrownBy(() -> merge.upsert(UPDATE_PRODUCT, List.of("3")))
                        .isInstanceOf(IllegalArgumentException.class);
            }
        }
    }

    private static String sql(MergeGenerator merge, UpsertSpec spec, String... values) {
        return merge.upsert(spec, List.of(values)).orElseThrow();
    }

    private static List<String> rows(Statement st, String sql) throws SQLException {
        List<String> out = new ArrayList<>();
        try (ResultSet rs = st.executeQuery(sql)) {
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
        }
        return out;
    }
}
