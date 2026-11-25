/*
 * Copyright (c) 2026 Contributors to the Eclipse Foundation.
 *
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.daanse.sql.dialect.db.clickhouse.container;

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
import org.eclipse.daanse.sql.dialect.db.clickhouse.ClickHouseDialect;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.testcontainers.clickhouse.ClickHouseContainer;

/**
 * ClickHouse has common table expressions on every version here, recursive ones from 24.4: {@code WITH RECURSIVE},
 * on the new analyzer only, which is on by default from 24.3. The dialect is built from a live connection: from 24.4
 * on the WITH clause it writes runs on the server, a recursive one counting and walking a tree, without RECURSIVE a
 * recursive one failing, and fails as well where the session turns the analyzer off; before, it says it has no
 * recursive ones and writes no RECURSIVE, which the server would refuse. Two of one clause, the second reading the
 * first, run on every version.
 */
@EnabledIfSystemProperty(named = "integration.docker", matches = "true")
class ClickHouseRecursiveCteTest {

    /** A tree: ceo above a and b, a above c, c above d. */
    private static final List<String> SETUP = List.of(
            "CREATE TABLE emp(id Int32, boss Nullable(Int32), name String) ENGINE = MergeTree() ORDER BY id",
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

    @ParameterizedTest(name = "clickhouse:{0} -> recursive {1}")
    @CsvSource({ "21.8, false", "23.8, false", "24.3, false", "24.4, true", "24.8, true", "25.8, true",
            "26.6, true" })
    void recursive_cte(String tag, boolean expected) throws Exception {
        try (ClickHouseContainer ch = ClickHouseContainers.container(tag)) {
            ch.start();
            try (Connection c = DriverManager.getConnection(ClickHouseContainers.jdbcUrl(ch, tag), ch.getUsername(),
                    ch.getPassword()); Statement st = c.createStatement()) {
                for (String sql : SETUP) {
                    st.execute(sql);
                }
                CteGenerator cte = new ClickHouseDialect(DialectInitData.fromConnection(c)).cteGenerator();

                assertThat(cte.supportsRecursiveCte()).as("supportsRecursiveCte").isEqualTo(expected);
                assertThat(cte.withClause(List.of(), true)).as("no expressions, no clause").isEmpty();
                assertThat(cte.withClause(NUMBERS, true).startsWith("WITH RECURSIVE ")).as("RECURSIVE written")
                        .isEqualTo(expected);
                assertThat(rows(st, cte.withClause(BOSSES, false) + "SELECT name FROM named ORDER BY name"))
                        .as("two of one clause, the second reading the first").containsExactly("a", "c", "ceo");

                if (!expected) {
                    assertThatThrownBy(() -> rows(st, "WITH RECURSIVE " + NUMBERS.get(0).name() + " AS ("
                            + NUMBERS.get(0).body() + ") SELECT sum(n) FROM nums"))
                            .as("the server refuses RECURSIVE before 24.4").isInstanceOf(SQLException.class)
                            .hasMessageContaining("Syntax error");
                    return;
                }

                assertThat(rows(st, cte.withClause(NUMBERS, true) + "SELECT toString(sum(n)) FROM nums"))
                        .as("counting to 5").containsExactly("15");
                assertThat(rows(st, cte.withClause(TREE, true)
                        + "SELECT concat(name, ':', toString(depth)) FROM tree ORDER BY depth, name"))
                        .as("walking the tree").containsExactly("ceo:0", "a:1", "b:1", "c:2", "d:3");
                assertThatThrownBy(() -> rows(st, cte.withClause(NUMBERS, false) + "SELECT sum(n) FROM nums"))
                        .as("a recursive one without RECURSIVE").isInstanceOf(SQLException.class);
                assertThatThrownBy(() -> rows(st, cte.withClause(NUMBERS, true)
                        + "SELECT sum(n) FROM nums SETTINGS allow_experimental_analyzer = 0"))
                        .as("not on the old analyzer").isInstanceOf(SQLException.class);
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
