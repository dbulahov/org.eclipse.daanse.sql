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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.daanse.sql.dialect.api.DialectInitData;
import org.eclipse.daanse.sql.dialect.db.mysql.MySqlDialect;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.testcontainers.containers.MySQLContainer;

/**
 * From MySQL 5.7, whose default sql_mode has ONLY_FULL_GROUP_BY, a {@code SELECT DISTINCT} may be ordered only by what
 * it selects: an order by a column it does not select fails with error 3065. The dialect is built from a live
 * connection, and where it says an order must use the aliases of the select list, an order by such a column must fail
 * and the order by the alias must not; where it does not, both must run. The order by an alias, its NULLs first or
 * last as the dialect writes them, runs on every version.
 */
@EnabledIfSystemProperty(named = "integration.docker", matches = "true")
class MySqlOrderByAliasTest {

    private static final List<String> SETUP = List.of("CREATE TABLE t(name VARCHAR(10), grp VARCHAR(1))",
            "INSERT INTO t VALUES ('b', 'x'), (NULL, 'x'), ('a', 'y'), ('b', 'y')");

    /** Ordered by a column the DISTINCT does not select. */
    private static final String BY_EXPRESSION = "SELECT DISTINCT UPPER(t.name) AS c0 FROM t ORDER BY t.name";

    /** The same order, by the alias of a column the DISTINCT selects. */
    private static final String BY_ALIAS = "SELECT DISTINCT UPPER(t.name) AS c0, t.name AS c1 FROM t ORDER BY c1";

    @ParameterizedTest(name = "mysql:{0} -> requires the alias {1}")
    @CsvSource({ "5.6, false", "5.7, true", "8.0, true", "8.4, true", "9, true" })
    void order_by_alias(String tag, boolean expected) throws Exception {
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

                assertThat(dialect.requiresOrderByAlias()).as("requiresOrderByAlias").isEqualTo(expected);
                if (expected) {
                    assertThat(dialect.allowsOrderByAlias()).as("an alias is allowed where it is required").isTrue();
                    assertThatThrownBy(() -> rows(st, BY_EXPRESSION))
                            .as("an order by a column the DISTINCT does not select").isInstanceOf(SQLException.class)
                            .hasMessageContaining("not in SELECT list");
                } else {
                    assertThat(rows(st, BY_EXPRESSION)).as("5.6 orders by any column").containsExactly(null, "A",
                            "B");
                }
                assertThat(rows(st, BY_ALIAS)).as("the order by the alias").containsExactly(null, "A", "B");

                String nullsLast = dialect.generateOrderByNulls("c1", true, true).toString();
                assertThat(rows(st, "SELECT DISTINCT UPPER(t.name) AS c0, t.name AS c1 FROM t ORDER BY " + nullsLast))
                        .as("the alias, its NULLs last: " + nullsLast).containsExactly("A", "B", null);
                String nullsFirst = dialect.generateOrderByNulls("c1", false, false).toString();
                assertThat(rows(st, "SELECT DISTINCT UPPER(t.name) AS c0, t.name AS c1 FROM t ORDER BY " + nullsFirst))
                        .as("the alias descending, its NULLs first: " + nullsFirst).containsExactly(null, "B", "A");
            }
        }
    }

    private static List<String> rows(Statement st, String sql) throws SQLException {
        List<String> out = new ArrayList<>();
        try (ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                out.add(rs.getString(1));
            }
        }
        return out;
    }
}
