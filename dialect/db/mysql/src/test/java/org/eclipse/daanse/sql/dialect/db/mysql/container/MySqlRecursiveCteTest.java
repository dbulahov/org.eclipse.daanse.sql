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
import org.eclipse.daanse.sql.dialect.api.generator.CteGenerator;
import org.eclipse.daanse.sql.dialect.api.generator.CteGenerator.Cte;
import org.eclipse.daanse.sql.dialect.db.mysql.MySqlDialect;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.testcontainers.containers.MySQLContainer;

/**
 * MySQL has common table expressions from 8.0, recursive ones too: {@code WITH RECURSIVE}. The dialect is built from a
 * live connection: from 8.0 on the WITH clause it writes runs on the server, a recursive one counting and walking a
 * tree, without RECURSIVE a recursive one failing, two of one clause the second reading the first. On 5.7 it says it has
 * no recursive ones and writes no RECURSIVE; 5.7 has no WITH at all, which no flag of the dialect says.
 */
@EnabledIfSystemProperty(named = "integration.docker", matches = "true")
class MySqlRecursiveCteTest {

    /** A tree: ceo above a and b, a above c, c above d. */
    private static final List<String> SETUP = List.of("CREATE TABLE emp(id INT PRIMARY KEY, boss INT, name VARCHAR(10))",
            "INSERT INTO emp VALUES (1, NULL, 'ceo'), (2, 1, 'a'), (3, 1, 'b'), (4, 2, 'c'), (5, 4, 'd')");

    private static final List<Cte> NUMBERS = List
            .of(new Cte("nums", "SELECT 1 AS n UNION ALL SELECT n + 1 FROM nums WHERE n < 5"));

    private static final List<Cte> TREE = List.of(new Cte("tree",
            "SELECT id, name, 0 AS depth FROM emp WHERE boss IS NULL"
                    + " UNION ALL SELECT e.id, e.name, t.depth + 1 FROM emp e JOIN tree t ON e.boss = t.id"));

    /** Two of one clause, not recursive, the second reading the first. */
    private static final List<Cte> BOSSES = List.of(
            new Cte("bosses", "SELECT DISTINCT boss FROM emp WHERE boss IS NOT NULL"),
            new Cte("named", "SELECT e.name FROM emp e JOIN bosses b ON e.id = b.boss"));

    @ParameterizedTest(name = "mysql:{0} -> recursive {1}")
    @CsvSource({ "5.7, false", "8.0, true", "8.4, true", "9, true" })
    void recursive_cte(String tag, boolean expected) throws Exception {
        // the server's own configuration: Testcontainers' default sets innodb_log_file_size, which MySQL 9 refuses
        try (MySQLContainer<?> my = new MySQLContainer<>("mysql:" + tag).withConfigurationOverride("mysql-conf")
                .withDatabaseName("rt").withUsername("rt").withPassword("rt")) {
            my.start();
            try (Connection c = DriverManager.getConnection(my.getJdbcUrl(), my.getUsername(), my.getPassword());
                    Statement st = c.createStatement()) {
                for (String sql : SETUP) {
                    st.execute(sql);
                }
                CteGenerator cte = new MySqlDialect(DialectInitData.fromConnection(c)).cteGenerator();

                assertThat(cte.supportsRecursiveCte()).as("supportsRecursiveCte").isEqualTo(expected);
                assertThat(cte.withClause(List.of(), true)).as("no expressions, no clause").isEmpty();
                assertThat(cte.withClause(NUMBERS, true).startsWith("WITH RECURSIVE ")).as("RECURSIVE written")
                        .isEqualTo(expected);

                if (!expected) {
                    assertThatThrownBy(() -> rows(st, cte.withClause(BOSSES, false) + "SELECT name FROM named"))
                            .as("5.7 knows no WITH, not recursive either; no flag says so")
                            .isInstanceOf(SQLException.class).hasMessageContaining("syntax");
                    return;
                }

                assertThat(rows(st, cte.withClause(NUMBERS, true) + "SELECT SUM(n) FROM nums"))
                        .as("counting to 5").containsExactly("15");
                assertThat(rows(st, cte.withClause(TREE, true)
                        + "SELECT CONCAT(name, ':', depth) FROM tree ORDER BY depth, name"))
                        .as("walking the tree").containsExactly("ceo:0", "a:1", "b:1", "c:2", "d:3");
                assertThatThrownBy(() -> rows(st, cte.withClause(NUMBERS, false) + "SELECT SUM(n) FROM nums"))
                        .as("a recursive one without RECURSIVE").isInstanceOf(SQLException.class)
                        .hasMessageContaining("nums");
                assertThat(rows(st, cte.withClause(BOSSES, false) + "SELECT name FROM named ORDER BY name"))
                        .as("two of one clause, the second reading the first").containsExactly("a", "c", "ceo");
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
