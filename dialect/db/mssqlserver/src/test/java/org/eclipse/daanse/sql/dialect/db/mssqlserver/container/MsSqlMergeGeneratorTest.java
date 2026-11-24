/*
 * Copyright (c) 2026 Contributors to the Eclipse Foundation.
 *
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.daanse.sql.dialect.db.mssqlserver.container;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.eclipse.daanse.sql.dialect.api.DialectInitData;
import org.eclipse.daanse.sql.dialect.api.generator.MergeGenerator;
import org.eclipse.daanse.sql.dialect.api.generator.MergeGenerator.UpsertSpec;
import org.eclipse.daanse.sql.dialect.db.mssqlserver.MicrosoftSqlServerDialect;
import org.eclipse.daanse.sql.model.schema.SchemaReference;
import org.eclipse.daanse.sql.model.schema.TableReference;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.containers.MSSQLServerContainer;

/**
 * SQL Server upserts with {@code MERGE INTO … USING (VALUES …) … WHEN MATCHED … WHEN NOT MATCHED …}, from 2008 on, so in
 * every version Docker serves, 2017 to 2025. An upsert inserts a row of a new key and, of a key that is there, updates
 * the update columns; without update columns it leaves that row as it is, insert-or-ignore, as {@link UpsertSpec}
 * says. The dialect is built from a live connection, and its upsert must do so on the server: of a single key and of a
 * composite one, of a quote and Unicode in a value, and of the same new keys upserted at once by several connections,
 * each key once and without an error.
 */
@EnabledIfSystemProperty(named = "integration.docker", matches = "true")
class MsSqlMergeGeneratorTest {

    private static final SchemaReference DBO = new SchemaReference(Optional.empty(), "dbo");
    private static final TableReference PRODUCTS = new TableReference(Optional.of(DBO), "products",
            TableReference.TYPE_TABLE);
    private static final TableReference STOCK = new TableReference(Optional.of(DBO), "stock",
            TableReference.TYPE_TABLE);
    private static final TableReference HITS = new TableReference(Optional.of(DBO), "hits", TableReference.TYPE_TABLE);

    private static final List<String> SETUP = List.of(
            "CREATE TABLE products(id INT PRIMARY KEY, name NVARCHAR(20), price INT)",
            "CREATE TABLE stock(store VARCHAR(10), product INT, qty INT, PRIMARY KEY (store, product))",
            "CREATE TABLE hits(k INT PRIMARY KEY, n INT)",
            "INSERT INTO products VALUES (1, 'apple', 10)", "INSERT INTO stock VALUES ('north', 1, 5)");

    private static final UpsertSpec UPDATE_PRODUCT = new UpsertSpec(PRODUCTS, List.of("id"),
            List.of("id", "name", "price"), List.of("name", "price"));
    private static final UpsertSpec IGNORE_PRODUCT = new UpsertSpec(PRODUCTS, List.of("id"),
            List.of("id", "name", "price"), List.of());
    private static final UpsertSpec IGNORE_STOCK = new UpsertSpec(STOCK, List.of("store", "product"),
            List.of("store", "product", "qty"), List.of());
    private static final UpsertSpec UPDATE_STOCK = new UpsertSpec(STOCK, List.of("store", "product"),
            List.of("store", "product", "qty"), List.of("qty"));
    private static final UpsertSpec UPDATE_HIT = new UpsertSpec(HITS, List.of("k"), List.of("k", "n"), List.of("n"));

    private static final int THREADS = 8;
    private static final int KEYS = 100;

    @ParameterizedTest(name = "mssql/server:{0}")
    @ValueSource(strings = { "2017-latest", "2019-latest", "2022-latest", "2025-latest" })
    void upsert(String tag) throws Exception {
        try (MSSQLServerContainer<?> ms = new MSSQLServerContainer<>("mcr.microsoft.com/mssql/server:" + tag)
                .acceptLicense()) {
            ms.start();
            try (Connection c = DriverManager.getConnection(ms.getJdbcUrl(), ms.getUsername(), ms.getPassword());
                    Statement st = c.createStatement()) {
                for (String sql : SETUP) {
                    st.execute(sql);
                }
                MergeGenerator merge = new MicrosoftSqlServerDialect(DialectInitData.fromConnection(c))
                        .mergeGenerator();
                assertThat(merge.supportsMerge()).isTrue();

                st.execute(sql(merge, UPDATE_PRODUCT, "2", "'banana'", "7"));
                st.execute(sql(merge, UPDATE_PRODUCT, "1", "'apple'", "12"));
                assertThat(rows(st, "SELECT id, name, price FROM products ORDER BY id"))
                        .as("with update columns: a new key inserted, the one there updated")
                        .containsExactly("1|apple|12", "2|banana|7");

                String ignoreThere = sql(merge, IGNORE_PRODUCT, "1", "'cherry'", "99");
                st.execute(ignoreThere);
                assertThat(rows(st, "SELECT id, name, price FROM products ORDER BY id"))
                        .as("without update columns the row of a key that is there is left: " + ignoreThere)
                        .containsExactly("1|apple|12", "2|banana|7");
                st.execute(sql(merge, IGNORE_PRODUCT, "3", "'cherry'", "99"));
                assertThat(rows(st, "SELECT id, name, price FROM products ORDER BY id"))
                        .as("without update columns a new key is inserted")
                        .containsExactly("1|apple|12", "2|banana|7", "3|cherry|99");

                st.execute(sql(merge, UPDATE_PRODUCT, "4", "N'it''s 日本'", "1"));
                assertThat(rows(st, "SELECT name FROM products WHERE id = 4")).as("a quote and Unicode in a value")
                        .containsExactly("it's 日本");

                st.execute(sql(merge, IGNORE_STOCK, "'north'", "1", "8"));
                st.execute(sql(merge, IGNORE_STOCK, "'south'", "1", "3"));
                assertThat(rows(st, "SELECT store, product, qty FROM stock ORDER BY store"))
                        .as("a composite key, without update columns").containsExactly("north|1|5", "south|1|3");
                st.execute(sql(merge, UPDATE_STOCK, "'north'", "1", "9"));
                st.execute(sql(merge, UPDATE_STOCK, "'north'", "2", "4"));
                assertThat(rows(st, "SELECT store, product, qty FROM stock ORDER BY store, product"))
                        .as("a composite key, with update columns")
                        .containsExactly("north|1|9", "north|2|4", "south|1|3");

                List<String> errors = concurrently(ms, merge);
                assertThat(errors).as("the same new keys upserted at once by " + THREADS + " connections").isEmpty();
                assertThat(rows(st, "SELECT COUNT(*), COUNT(DISTINCT k) FROM hits")).as("each key once")
                        .containsExactly(KEYS + "|" + KEYS);
            }
        }
    }

    /** Every connection upserts the keys 1 to KEYS, all starting together; the errors they met. */
    private static List<String> concurrently(MSSQLServerContainer<?> ms, MergeGenerator merge) throws Exception {
        List<String> errors = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
            List<Future<?>> done = new ArrayList<>();
            for (int t = 0; t < THREADS; t++) {
                int thread = t;
                done.add(pool.submit(() -> {
                    try (Connection c = DriverManager.getConnection(ms.getJdbcUrl(), ms.getUsername(),
                            ms.getPassword()); Statement st = c.createStatement()) {
                        start.await();
                        for (int k = 1; k <= KEYS; k++) {
                            try {
                                st.execute(sql(merge, UPDATE_HIT, Integer.toString(k), Integer.toString(thread)));
                            } catch (SQLException e) {
                                errors.add("key " + k + ": " + e.getMessage());
                            }
                        }
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> f : done) {
                f.get();
            }
        } finally {
            pool.shutdownNow();
        }
        return errors;
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
