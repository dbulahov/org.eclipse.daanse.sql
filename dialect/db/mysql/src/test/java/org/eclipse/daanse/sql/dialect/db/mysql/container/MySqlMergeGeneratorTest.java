/*
 * Copyright (c) 2026 Contributors to the Eclipse Foundation.
 *
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.daanse.sql.dialect.db.mysql.container;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.eclipse.daanse.sql.dialect.api.DialectInitData;
import org.eclipse.daanse.sql.dialect.api.generator.MergeGenerator;
import org.eclipse.daanse.sql.dialect.api.generator.MergeGenerator.UpsertSpec;
import org.eclipse.daanse.sql.dialect.db.mysql.MySqlDialect;
import org.eclipse.daanse.sql.model.schema.SchemaReference;
import org.eclipse.daanse.sql.model.schema.TableReference;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.containers.MySQLContainer;

/**
 * An upsert inserts a row of a new key and, of a key that is there, updates the update columns; without update columns
 * it leaves that row as it is, insert-or-ignore, as {@link UpsertSpec} says. The dialect is built from a live
 * connection, and its upsert must do so on the server: of a single key and of a composite one, without an error on the
 * key that is there.
 */
@EnabledIfSystemProperty(named = "integration.docker", matches = "true")
class MySqlMergeGeneratorTest {

    private static final SchemaReference RT = new SchemaReference(Optional.empty(), "rt");
    private static final TableReference PRODUCTS = new TableReference(Optional.of(RT), "products",
            TableReference.TYPE_TABLE);
    private static final TableReference STOCK = new TableReference(Optional.of(RT), "stock", TableReference.TYPE_TABLE);

    private static final List<String> SETUP = List.of(
            "CREATE TABLE products(id INT PRIMARY KEY, name VARCHAR(20), price INT)",
            "CREATE TABLE stock(store VARCHAR(10), product INT, qty INT, PRIMARY KEY (store, product))",
            "INSERT INTO products VALUES (1, 'apple', 10)", "INSERT INTO stock VALUES ('north', 1, 5)");

    private static final UpsertSpec UPDATE_PRODUCT = new UpsertSpec(PRODUCTS, List.of("id"),
            List.of("id", "name", "price"), List.of("name", "price"));
    private static final UpsertSpec IGNORE_PRODUCT = new UpsertSpec(PRODUCTS, List.of("id"),
            List.of("id", "name", "price"), List.of());
    private static final UpsertSpec IGNORE_STOCK = new UpsertSpec(STOCK, List.of("store", "product"),
            List.of("store", "product", "qty"), List.of());

    @ParameterizedTest(name = "mysql:{0}")
    @ValueSource(strings = { "5.7", "8.0", "8.4", "9" })
    void upsert(String tag) throws Exception {
        // the server's own configuration: Testcontainers' default sets innodb_log_file_size, which MySQL 9 refuses
        try (MySQLContainer<?> my = new MySQLContainer<>("mysql:" + tag).withConfigurationOverride("mysql-conf")
                .withDatabaseName("rt").withUsername("rt").withPassword("rt")) {
            my.start();
            try (Connection c = DriverManager.getConnection(my.getJdbcUrl(), my.getUsername(), my.getPassword());
                    Statement st = c.createStatement()) {
                for (String sql : SETUP) {
                    st.execute(sql);
                }
                MergeGenerator merge = new MySqlDialect(DialectInitData.fromConnection(c)).mergeGenerator();
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

                st.execute(sql(merge, IGNORE_STOCK, "'north'", "1", "8"));
                st.execute(sql(merge, IGNORE_STOCK, "'south'", "1", "3"));
                assertThat(rows(st, "SELECT store, product, qty FROM stock ORDER BY store"))
                        .as("a composite key, without update columns").containsExactly("north|1|5", "south|1|3");
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
