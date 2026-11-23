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
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import org.eclipse.daanse.sql.dialect.api.DialectInitData;
import org.eclipse.daanse.sql.dialect.db.mysql.MySqlDialect;
import org.eclipse.daanse.sql.model.sql.NullsOrder;
import org.eclipse.daanse.sql.model.sql.OrderedColumn;
import org.eclipse.daanse.sql.model.sql.SortDirection;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.containers.MySQLContainer;

/**
 * MySQL lists the values of a group with {@code GROUP_CONCAT(DISTINCT x ORDER BY … SEPARATOR 's')}. The dialect is
 * built from a live connection, and the list it writes must come out of the server in the order asked for, the NULLs
 * of an order column first or last as asked, without the NULL values, DISTINCT kept, with any separator, of a column
 * of any type and per group. A list longer than the session's group_concat_max_len, 1024 bytes unless the connection
 * sets more, is cut with warning 1260: no expression of the list lifts it, so no list here is that long.
 */
@EnabledIfSystemProperty(named = "integration.docker", matches = "true")
class MySqlListAggTest {

    /** Group a: alice 20, bob 30, bob 10, a NULL name of 40; group b: dave 5. */
    private static final List<String> SETUP = List.of(
            "CREATE TABLE emp(dept VARCHAR(1), name VARCHAR(10), salary INT)",
            "INSERT INTO emp VALUES ('a', 'bob', 30), ('a', NULL, 40), ('a', 'alice', 20), ('a', 'bob', 10),"
                    + " ('b', 'dave', 5)");

    @ParameterizedTest(name = "mysql:{0}")
    @ValueSource(strings = { "5.7", "8.0", "8.4", "9" })
    void list_agg(String tag) throws Exception {
        // the server's own configuration: Testcontainers' default sets innodb_log_file_size, which MySQL 9 refuses
        try (MySQLContainer<?> my = new MySQLContainer<>("mysql:" + tag).withConfigurationOverride("mysql-conf")
                .withDatabaseName("rt").withUsername("rt").withPassword("rt")) {
            my.start();
            try (Connection c = DriverManager.getConnection(my.getJdbcUrl(), my.getUsername(), my.getPassword());
                    Statement st = c.createStatement()) {
                for (String sql : SETUP) {
                    st.execute(sql);
                }
                MySqlDialect dialect = new MySqlDialect(DialectInitData.fromConnection(c));
                assertThat(dialect.supportsListAgg()).isTrue();

                assertThat(list(st, dialect, "name", false, ", ", order("name", SortDirection.ASC)))
                        .as("ordered by the value, the NULL left out").isEqualTo("alice, bob, bob");
                assertThat(list(st, dialect, "name", false, ", ", order("salary", SortDirection.DESC)))
                        .as("ordered by another column").isEqualTo("bob, alice, bob");
                assertThat(list(st, dialect, "name", false, ", ",
                        List.of(new OrderedColumn("name", "emp", SortDirection.ASC))))
                        .as("a column of the table").isEqualTo("alice, bob, bob");

                assertThat(sorted(list(st, dialect, "name", true, ",", List.of())))
                        .as("DISTINCT without an order").containsExactly("alice", "bob");
                assertThat(list(st, dialect, "name", true, ",", order("name", SortDirection.ASC)))
                        .as("DISTINCT with an order: MySQL keeps both").isEqualTo("alice,bob");

                assertThat(list(st, dialect, "name", false, null, order("name", SortDirection.ASC)))
                        .as("no separator is a comma").isEqualTo("alice,bob,bob");
                assertThat(list(st, dialect, "name", false, "'", order("name", SortDirection.ASC)))
                        .as("a quote as separator").isEqualTo("alice'bob'bob");
                assertThat(list(st, dialect, "name", false, "\\", order("name", SortDirection.ASC)))
                        .as("a backslash as separator").isEqualTo("alice\\bob\\bob");

                assertThat(list(st, dialect, "salary", false, ",", order("salary", SortDirection.ASC)))
                        .as("an INT column").isEqualTo("10,20,30,40");
                assertThat(list(st, dialect, "salary", false, ",",
                        List.of(new OrderedColumn("name", null, Optional.of(SortDirection.ASC),
                                Optional.of(NullsOrder.FIRST)), new OrderedColumn("salary", null, SortDirection.ASC))))
                        .as("NULLS FIRST, then a second column").isEqualTo("40,20,10,30");
                assertThat(list(st, dialect, "salary", false, ",",
                        List.of(new OrderedColumn("name", null, Optional.of(SortDirection.ASC),
                                Optional.of(NullsOrder.LAST)), new OrderedColumn("salary", null, SortDirection.ASC))))
                        .as("NULLS LAST, then a second column").isEqualTo("20,10,30,40");

                String perDept = dialect.generateListAgg("name", false, ",", null, null,
                        order("name", SortDirection.ASC)).orElseThrow();
                assertThat(rows(st, "SELECT dept, " + perDept + " FROM emp GROUP BY dept ORDER BY dept"))
                        .as("per group").containsExactly("a=alice,bob,bob", "b=dave");
            }
        }
    }

    private static List<OrderedColumn> order(String column, SortDirection direction) {
        return List.of(new OrderedColumn(column, null, direction));
    }

    private static String list(Statement st, MySqlDialect dialect, String operand, boolean distinct, String separator,
            List<OrderedColumn> columns) throws SQLException {
        String agg = dialect.generateListAgg(operand, distinct, separator, null, null, columns).orElseThrow();
        try (ResultSet rs = st.executeQuery("SELECT " + agg + " FROM emp WHERE dept = 'a'")) {
            assertThat(rs.next()).isTrue();
            return rs.getString(1);
        } catch (SQLException e) {
            throw new SQLException(e.getMessage() + " in " + agg, e);
        }
    }

    private static List<String> sorted(String list) {
        return Arrays.stream(list.split(",")).sorted().toList();
    }

    private static List<String> rows(Statement st, String sql) throws SQLException {
        List<String> out = new ArrayList<>();
        try (ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                out.add(rs.getString(1) + "=" + rs.getString(2));
            }
        }
        return out;
    }
}
