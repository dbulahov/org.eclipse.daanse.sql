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
import java.sql.SQLWarning;
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
import org.junit.jupiter.params.provider.CsvSource;
import org.testcontainers.containers.MySQLContainer;

/**
 * An upsert of a key that is there sets each update column to the value the statement would have inserted. MySQL
 * reads that value with {@code VALUES(col)} in {@code ON DUPLICATE KEY UPDATE}, which 8.0.20 deprecates for the row
 * alias of 8.0.19, {@code INSERT … AS new … UPDATE col = new.col}, with warning 1287. The dialect is built from a live
 * connection, and its upsert must update the update columns only, of a single key and of a composite one, and the
 * server must warn of nothing: the alias from 8.0.19, {@code VALUES(col)} before.
 */
@EnabledIfSystemProperty(named = "integration.docker", matches = "true")
class MySqlUpsertValuesTest {

    private static final SchemaReference RT = new SchemaReference(Optional.empty(), "rt");
    private static final TableReference PRODUCTS = new TableReference(Optional.of(RT), "products",
            TableReference.TYPE_TABLE);
    private static final TableReference STOCK = new TableReference(Optional.of(RT), "stock", TableReference.TYPE_TABLE);

    private static final List<String> SETUP = List.of(
            "CREATE TABLE products(id INT PRIMARY KEY, name VARCHAR(20), price INT, note VARCHAR(20))",
            "CREATE TABLE stock(store VARCHAR(10), product INT, qty INT, PRIMARY KEY (store, product))",
            "INSERT INTO products VALUES (1, 'apple', 10, 'kept')", "INSERT INTO stock VALUES ('north', 1, 5)");

    /** note is inserted but not updated: a key that is there keeps it. */
    private static final UpsertSpec PRODUCT = new UpsertSpec(PRODUCTS, List.of("id"),
            List.of("id", "name", "price", "note"), List.of("name", "price"));
    private static final UpsertSpec ONE_COLUMN = new UpsertSpec(PRODUCTS, List.of("id"), List.of("id", "price"),
            List.of("price"));
    private static final UpsertSpec STOCK_QTY = new UpsertSpec(STOCK, List.of("store", "product"),
            List.of("store", "product", "qty"), List.of("qty"));

    @ParameterizedTest(name = "mysql:{0} -> row alias {1}")
    @CsvSource({ "5.7, false", "8.0, true", "8.4, true", "9, true" })
    void upsert_values(String tag, boolean alias) throws Exception {
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

                upsert(st, merge, alias, PRODUCT, "1", "'apple'", "12", "'new'");
                assertThat(rows(st, "SELECT id, name, price, note FROM products ORDER BY id"))
                        .as("the update columns updated, note kept").containsExactly("1|apple|12|kept");

                upsert(st, merge, alias, PRODUCT, "2", "'banana'", "7", "'fresh'");
                assertThat(rows(st, "SELECT id, name, price, note FROM products ORDER BY id"))
                        .as("a new key inserted with every column").containsExactly("1|apple|12|kept", "2|banana|7|fresh");

                upsert(st, merge, alias, ONE_COLUMN, "2", "8");
                assertThat(rows(st, "SELECT id, name, price, note FROM products ORDER BY id"))
                        .as("one update column").containsExactly("1|apple|12|kept", "2|banana|8|fresh");

                upsert(st, merge, alias, STOCK_QTY, "'north'", "1", "9");
                upsert(st, merge, alias, STOCK_QTY, "'south'", "1", "3");
                assertThat(rows(st, "SELECT store, product, qty FROM stock ORDER BY store")).as("a composite key")
                        .containsExactly("north|1|9", "south|1|3");
            }
        }
    }

    /** Runs the dialect's upsert, written with the row alias or VALUES(col), and asserts the server warned of nothing. */
    private static void upsert(Statement st, MergeGenerator merge, boolean alias, UpsertSpec spec, String... values)
            throws SQLException {
        String sql = merge.upsert(spec, List.of(values)).orElseThrow();
        assertThat(sql).as("the row alias").contains(alias ? "AS `new_row`" : "VALUES(`");
        assertThat(sql).as("the row alias").doesNotContain(alias ? "VALUES(`" : "new_row");
        st.clearWarnings();
        st.execute(sql);
        List<String> warnings = new ArrayList<>();
        for (SQLWarning w = st.getWarnings(); w != null; w = w.getNextWarning()) {
            warnings.add(w.getErrorCode() + ": " + w.getMessage());
        }
        assertThat(warnings).as("warnings of " + sql).isEmpty();
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
