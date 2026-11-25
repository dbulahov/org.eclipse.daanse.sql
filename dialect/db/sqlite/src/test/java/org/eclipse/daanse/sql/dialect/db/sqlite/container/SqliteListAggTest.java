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

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.eclipse.daanse.sql.dialect.db.sqlite.SqliteDialect;
import org.eclipse.daanse.sql.model.sql.NullsOrder;
import org.eclipse.daanse.sql.model.sql.OrderedColumn;
import org.eclipse.daanse.sql.model.sql.SortDirection;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * SQLite lists the values of a group with {@code group_concat(x, sep)} on every version, with an order inside it
 * from 3.44.0; a DISTINCT aggregate takes one argument only, on every version. SQLite is embedded: the CLI of each
 * version runs the statements in Docker, and the dialect is built for the version the CLI reports. The list it writes
 * must leave the NULL values out, keep DISTINCT with any separator, take a quote and a backslash as a separator, list
 * a column of any type and per group; from 3.44 on in the order asked for, the NULLs of an order column first or last
 * as asked. Before 3.44 an ordered list, and on every version a DISTINCT one in the order of another column, it does
 * not write.
 */
@EnabledIfSystemProperty(named = "integration.docker", matches = "true")
class SqliteListAggTest {

    /** Group a: alice 20, bob 30, bob 10, a NULL name of 40; group b: dave 5. */
    private static final String SETUP = "CREATE TABLE emp(dept TEXT, name TEXT, salary INTEGER);"
            + " INSERT INTO emp VALUES ('a', 'bob', 30), ('a', NULL, 40), ('a', 'alice', 20), ('a', 'bob', 10),"
            + " ('b', 'dave', 5);\n";

    @ParameterizedTest(name = "sqlite {0} -> ordered {1}")
    @CsvSource({ "3.22.0, false", "3.32.1, false", "3.43.1, false", "3.44.0, true", "3.46.1, true",
            "3.51.0, true" })
    void list_agg(String version, boolean ordered) {
        SqliteDialect d = SqliteCli.dialect(version);
        assertThat(d.supportsListAgg()).as("supportsListAgg").isTrue();

        Map<String, String> lists = new LinkedHashMap<>();
        lists.put("no order", agg(d, "name", false, ",", List.of()));
        lists.put("DISTINCT, no order", agg(d, "name", true, ";", List.of()));
        lists.put("a quote as separator", agg(d, "name", false, "'", List.of()));
        lists.put("a backslash as separator", agg(d, "name", false, "\\", List.of()));
        lists.put("no separator is a comma", agg(d, "name", false, null, List.of()));
        lists.put("an INTEGER column", agg(d, "salary", false, ",", List.of()));
        if (ordered) {
            lists.put("ordered by the value", agg(d, "name", false, ", ", order("name", SortDirection.ASC)));
            lists.put("ordered by another column", agg(d, "name", false, ", ", order("salary", SortDirection.DESC)));
            lists.put("a column of the table", agg(d, "name", false, ", ",
                    List.of(new OrderedColumn("name", "emp", SortDirection.ASC))));
            lists.put("DISTINCT ordered by the value", agg(d, "name", true, ";", order("name", SortDirection.DESC)));
            lists.put("NULLS FIRST, then a second column", agg(d, "salary", false, ",",
                    List.of(new OrderedColumn("name", null, Optional.of(SortDirection.ASC),
                            Optional.of(NullsOrder.FIRST)), new OrderedColumn("salary", null, SortDirection.ASC))));
            lists.put("NULLS LAST, then a second column", agg(d, "salary", false, ",",
                    List.of(new OrderedColumn("name", null, Optional.of(SortDirection.ASC),
                            Optional.of(NullsOrder.LAST)), new OrderedColumn("salary", null, SortDirection.ASC))));
        } else {
            assertThat(d.generateListAgg("name", false, ",", null, null, order("name", SortDirection.ASC)))
                    .as("no ordered list before 3.44").isEmpty();
        }
        assertThat(d.generateListAgg("name", true, ",", null, null, order("salary", SortDirection.ASC)))
                .as("no DISTINCT list in the order of another column").isEmpty();

        StringBuilder sql = new StringBuilder(SETUP);
        lists.forEach((label, agg) -> sql.append("SELECT '").append(label).append("|' || ").append(agg)
                .append(" FROM emp WHERE dept = 'a';\n"));
        String perDept = agg(d, "name", false, ",", List.of());
        sql.append("SELECT 'per group|' || group_concat(dept || '=' || l, ' ') FROM (SELECT dept, ").append(perDept)
                .append(" AS l FROM emp GROUP BY dept ORDER BY dept);\n");
        String distinctPerDept = agg(d, "name", true, ";", List.of());
        sql.append("SELECT 'DISTINCT per group|' || group_concat(dept || '=' || l, ' ') FROM (SELECT dept, ")
                .append(distinctPerDept).append(" AS l FROM emp GROUP BY dept ORDER BY dept);\n");
        SqliteCli.Result r = SqliteCli.run(version, sql.toString());
        assertThat(r.failed()).as("every list runs: " + r.err() + " in " + sql).isFalse();
        Map<String, String> got = new LinkedHashMap<>();
        for (String line : r.out()) {
            int bar = line.indexOf('|');
            if (bar > 0) {
                got.put(line.substring(0, bar), line.substring(bar + 1));
            }
        }

        assertThat(sorted(got.get("no order"), ",")).as("the NULL left out").containsExactly("alice", "bob", "bob");
        assertThat(sorted(got.get("DISTINCT, no order"), ";")).as(lists.get("DISTINCT, no order"))
                .containsExactly("alice", "bob");
        assertThat(sorted(got.get("a quote as separator"), "'")).containsExactly("alice", "bob", "bob");
        assertThat(sorted(got.get("a backslash as separator"), "\\\\")).containsExactly("alice", "bob", "bob");
        assertThat(sorted(got.get("no separator is a comma"), ",")).containsExactly("alice", "bob", "bob");
        assertThat(sorted(got.get("an INTEGER column"), ",")).containsExactly("10", "20", "30", "40");
        assertThat(got.get("per group")).as("per group")
                .matches("a=(alice,bob,bob|bob,alice,bob|bob,bob,alice) b=dave");
        assertThat(got.get("DISTINCT per group")).as("DISTINCT per group").matches("a=(alice;bob|bob;alice) b=dave");
        if (!ordered) {
            SqliteCli.Result refused = SqliteCli.run(version,
                    SETUP + "SELECT group_concat(name, ',' ORDER BY name) FROM emp;");
            assertThat(refused.failed()).as("the server knows no ORDER BY in an aggregate before 3.44").isTrue();
            return;
        }
        assertThat(got.get("ordered by the value")).as(lists.get("ordered by the value"))
                .isEqualTo("alice, bob, bob");
        assertThat(got.get("ordered by another column")).isEqualTo("bob, alice, bob");
        assertThat(got.get("a column of the table")).isEqualTo("alice, bob, bob");
        assertThat(got.get("DISTINCT ordered by the value")).as(lists.get("DISTINCT ordered by the value"))
                .isEqualTo("bob;alice");
        assertThat(got.get("NULLS FIRST, then a second column")).isEqualTo("40,20,10,30");
        assertThat(got.get("NULLS LAST, then a second column")).isEqualTo("20,10,30,40");
    }

    private static String agg(SqliteDialect d, String operand, boolean distinct, String separator,
            List<OrderedColumn> columns) {
        return d.generateListAgg(operand, distinct, separator, null, null, columns).orElseThrow();
    }

    private static List<OrderedColumn> order(String column, SortDirection direction) {
        return List.of(new OrderedColumn(column, null, direction));
    }

    private static List<String> sorted(String list, String separatorRegex) {
        assertThat(list).isNotNull();
        return Arrays.stream(list.split(separatorRegex)).sorted().toList();
    }
}
