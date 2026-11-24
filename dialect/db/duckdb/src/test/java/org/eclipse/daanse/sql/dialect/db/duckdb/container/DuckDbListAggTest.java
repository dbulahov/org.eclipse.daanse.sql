/*
 * Copyright (c) 2026 Contributors to the Eclipse Foundation.
 *
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.daanse.sql.dialect.db.duckdb.container;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.eclipse.daanse.sql.dialect.db.duckdb.DuckDbDialect;
import org.eclipse.daanse.sql.model.sql.NullsOrder;
import org.eclipse.daanse.sql.model.sql.OrderedColumn;
import org.eclipse.daanse.sql.model.sql.SortDirection;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * DuckDB lists the values of a group with {@code STRING_AGG([DISTINCT] x, 's' ORDER BY …)}, in every version from 1.0
 * on. The CLI of each version runs the lists the dialect writes, in Docker, and the dialect is built for the version
 * the CLI reports: each list must come out in the order asked for, the NULLs of an order column first or last as
 * asked, without the NULL values, with any separator, a quote too, of a column of any type and per group. DISTINCT is
 * kept with an order by the value itself; with an order by another column DuckDB refuses DISTINCT ("ORDER BY
 * expressions must appear in the argument list"), and the dialect leaves it out.
 */
@EnabledIfSystemProperty(named = "integration.docker", matches = "true")
class DuckDbListAggTest {

    /** Group a: alice 20, bob 30, bob 10, a NULL name of 40; group b: dave 5. */
    private static final String SETUP = "CREATE TABLE emp(dept VARCHAR, name VARCHAR, salary INT);"
            + " INSERT INTO emp VALUES ('a', 'bob', 30), ('a', NULL, 40), ('a', 'alice', 20), ('a', 'bob', 10),"
            + " ('b', 'dave', 5);";

    @ParameterizedTest(name = "duckdb:{0}")
    @ValueSource(strings = { "1.0.0", "1.1.3", "1.2.2", "1.3.2", "1.4.4", "1.5.6", "2.0.1" })
    void listAgg(String tag) {
        DuckDbDialect dialect = DuckDbCli.dialect(tag);
        assertThat(dialect.supportsListAgg()).as("supportsListAgg").isTrue();

        Map<String, String> lists = new LinkedHashMap<>();
        lists.put("by value", list(dialect, "name", false, ", ", order("name", SortDirection.ASC)));
        lists.put("by another column", list(dialect, "name", false, ", ", order("salary", SortDirection.DESC)));
        lists.put("by a column of the table", list(dialect, "name", false, ", ",
                List.of(new OrderedColumn("name", "emp", SortDirection.ASC))));
        lists.put("DISTINCT", list(dialect, "name", true, ",", List.of()));
        lists.put("DISTINCT by value", list(dialect, "name", true, ",", order("name", SortDirection.ASC)));
        lists.put("DISTINCT by another column", list(dialect, "name", true, ",", order("salary", SortDirection.ASC)));
        lists.put("no separator", list(dialect, "name", false, null, order("name", SortDirection.ASC)));
        lists.put("a quote", list(dialect, "name", false, "'", order("name", SortDirection.ASC)));
        lists.put("a backslash", list(dialect, "name", false, "\\", order("name", SortDirection.ASC)));
        lists.put("an INT column", list(dialect, "salary", false, ",", order("salary", SortDirection.ASC)));
        lists.put("NULLS FIRST", list(dialect, "salary", false, ",",
                List.of(new OrderedColumn("name", null, Optional.of(SortDirection.ASC), Optional.of(NullsOrder.FIRST)),
                        new OrderedColumn("salary", null, SortDirection.ASC))));
        lists.put("NULLS LAST", list(dialect, "salary", false, ",",
                List.of(new OrderedColumn("name", null, Optional.of(SortDirection.ASC), Optional.of(NullsOrder.LAST)),
                        new OrderedColumn("salary", null, SortDirection.ASC))));

        StringBuilder sql = new StringBuilder(SETUP);
        lists.forEach((label, agg) -> sql.append(" SELECT '").append(label).append("=' || ").append(agg)
                .append(" FROM emp WHERE dept = 'a';"));
        String perDept = list(dialect, "name", false, ",", order("name", SortDirection.ASC));
        sql.append(" SELECT 'per group ' || dept || '=' || ").append(perDept)
                .append(" FROM emp GROUP BY dept ORDER BY dept;");

        DuckDbCli.Result r = DuckDbCli.run(tag, sql.toString());
        assertThat(r.failed()).as("every list runs: " + r.err() + " in " + lists).isFalse();
        Map<String, String> got = new LinkedHashMap<>();
        for (String line : r.out()) {
            int eq = line.indexOf('=');
            got.put(line.substring(0, eq), line.substring(eq + 1));
        }

        assertThat(got.get("by value")).as("ordered by the value, the NULL left out").isEqualTo("alice, bob, bob");
        assertThat(got.get("by another column")).as("ordered by another column").isEqualTo("bob, alice, bob");
        assertThat(got.get("by a column of the table")).as("a column of the table").isEqualTo("alice, bob, bob");
        assertThat(sorted(got.get("DISTINCT"))).as("DISTINCT without an order").containsExactly("alice", "bob");
        assertThat(got.get("DISTINCT by value")).as("DISTINCT with an order by the value: both kept")
                .isEqualTo("alice,bob");
        assertThat(got.get("DISTINCT by another column")).as("DISTINCT left out with an order by another column")
                .isEqualTo("bob,alice,bob");
        assertThat(got.get("no separator")).as("no separator is a comma").isEqualTo("alice,bob,bob");
        assertThat(got.get("a quote")).as("a quote as separator").isEqualTo("alice'bob'bob");
        assertThat(got.get("a backslash")).as("a backslash as separator").isEqualTo("alice\\bob\\bob");
        assertThat(got.get("an INT column")).as("an INT column").isEqualTo("10,20,30,40");
        assertThat(got.get("NULLS FIRST")).as("NULLS FIRST, then a second column").isEqualTo("40,20,10,30");
        assertThat(got.get("NULLS LAST")).as("NULLS LAST, then a second column").isEqualTo("20,10,30,40");
        assertThat(got.get("per group a")).as("per group").isEqualTo("alice,bob,bob");
        assertThat(got.get("per group b")).as("per group").isEqualTo("dave");

        DuckDbCli.Result refused = DuckDbCli.run(tag,
                SETUP + " SELECT STRING_AGG(DISTINCT name, ',' ORDER BY salary) FROM emp;");
        assertThat(refused.failed()).as("DISTINCT ordered by another column is refused").isTrue();
        assertThat(refused.err()).contains("ORDER BY expressions must appear in the argument list");
    }

    private static List<OrderedColumn> order(String column, SortDirection direction) {
        return List.of(new OrderedColumn(column, null, direction));
    }

    private static String list(DuckDbDialect dialect, String operand, boolean distinct, String separator,
            List<OrderedColumn> columns) {
        return dialect.generateListAgg(operand, distinct, separator, null, null, columns).orElseThrow();
    }

    private static List<String> sorted(String list) {
        return Arrays.stream(list.split(",")).sorted().toList();
    }
}
