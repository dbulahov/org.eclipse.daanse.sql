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
 * DuckDB gives the n-th value of a group as the n-th element of its ordered list,
 * {@code list_extract(list(v ORDER BY …) [FILTER (WHERE v IS NOT NULL)], n)}, in every version from 1.0 on: an
 * aggregate over the rows of the group, not the window {@code NTH_VALUE … OVER} (V-82). The CLI of each version runs
 * what the dialect writes, in Docker, and the dialect is built for the version the CLI reports: the n-th value must
 * follow the order asked for, ascending, descending, NULLs of an order column first or last, a column named with its
 * table; it counts the NULL values or leaves them out as asked; n below 1 is 1; past the end it is NULL; and it is
 * per group.
 */
@EnabledIfSystemProperty(named = "integration.docker", matches = "true")
class DuckDbNthValueContainerTest {

    /** Group a in the order of o: NULL, 20, 30, NULL, 50; group b: 7; group c: a NULL o of 9, then 8. */
    private static final String SETUP = "CREATE TABLE t(g VARCHAR, o INT, v INT);"
            + " INSERT INTO t VALUES ('a', 3, 30), ('a', 1, NULL), ('a', 5, 50), ('a', 2, 20), ('a', 4, NULL),"
            + " ('b', 1, 7), ('c', NULL, 9), ('c', 1, 8);";

    @ParameterizedTest(name = "duckdb:{0}")
    @ValueSource(strings = { "1.0.0", "1.1.3", "1.2.2", "1.3.2", "1.4.4", "1.5.6", "2.0.1" })
    void nthValue(String tag) {
        DuckDbDialect dialect = DuckDbCli.dialect(tag);
        assertThat(dialect.supportsNthValue()).as("supportsNthValue").isTrue();
        assertThat(dialect.supportsNthValueIgnoreNulls()).as("supportsNthValueIgnoreNulls").isTrue();

        List<OrderedColumn> byO = List.of(new OrderedColumn("o", null, SortDirection.ASC));
        List<OrderedColumn> byODesc = List.of(new OrderedColumn("o", null, SortDirection.DESC));
        Map<String, String> nth = new LinkedHashMap<>();
        nth.put("1st", nth(dialect, false, 1, byO));
        nth.put("1st ignoring NULLs", nth(dialect, true, 1, byO));
        nth.put("2nd", nth(dialect, false, 2, byO));
        nth.put("2nd ignoring NULLs", nth(dialect, true, 2, byO));
        nth.put("5th", nth(dialect, false, 5, byO));
        nth.put("3rd ignoring NULLs", nth(dialect, true, 3, byO));
        nth.put("4th ignoring NULLs, past the end", nth(dialect, true, 4, byO));
        nth.put("6th, past the end", nth(dialect, false, 6, byO));
        nth.put("0th is the 1st", nth(dialect, true, 0, byO));
        nth.put("no n is the 1st", nth(dialect, true, null, byO));
        nth.put("1st descending", nth(dialect, false, 1, byODesc));
        nth.put("2nd descending ignoring NULLs", nth(dialect, true, 2, byODesc));
        nth.put("1st by a column of the table", nth(dialect, true, 1,
                List.of(new OrderedColumn("o", "t", SortDirection.ASC))));

        StringBuilder sql = new StringBuilder(SETUP);
        nth.forEach((label, agg) -> sql.append(" SELECT '").append(label).append("', ").append(shown(agg))
                .append(" FROM t WHERE g = 'a';"));
        nullsOrder(sql, dialect, "NULLS FIRST", NullsOrder.FIRST);
        nullsOrder(sql, dialect, "NULLS LAST", NullsOrder.LAST);
        sql.append(" SELECT 'per group ' || g, ").append(shown(nth(dialect, true, 1, byO)))
                .append(" FROM t GROUP BY g ORDER BY g;");

        DuckDbCli.Result r = DuckDbCli.run(tag, sql.toString());
        assertThat(r.failed()).as("every n-th value runs: " + r.err() + " in " + nth).isFalse();
        Map<String, String> got = new LinkedHashMap<>();
        for (String line : r.out()) {
            int bar = line.indexOf('|');
            got.put(line.substring(0, bar), line.substring(bar + 1));
        }

        assertThat(got.get("1st")).as("the NULLs counted: " + nth.get("1st")).isEqualTo("null");
        assertThat(got.get("1st ignoring NULLs")).as(nth.get("1st ignoring NULLs")).isEqualTo("20");
        assertThat(got.get("2nd")).isEqualTo("20");
        assertThat(got.get("2nd ignoring NULLs")).isEqualTo("30");
        assertThat(got.get("5th")).isEqualTo("50");
        assertThat(got.get("3rd ignoring NULLs")).isEqualTo("50");
        assertThat(got.get("4th ignoring NULLs, past the end")).isEqualTo("null");
        assertThat(got.get("6th, past the end")).isEqualTo("null");
        assertThat(got.get("0th is the 1st")).isEqualTo("20");
        assertThat(got.get("no n is the 1st")).isEqualTo("20");
        assertThat(got.get("1st descending")).isEqualTo("50");
        assertThat(got.get("2nd descending ignoring NULLs")).isEqualTo("30");
        assertThat(got.get("1st by a column of the table")).isEqualTo("20");
        assertThat(got.get("NULLS FIRST")).as("the row of a NULL order first").isEqualTo("9");
        assertThat(got.get("NULLS LAST")).as("the row of a NULL order last").isEqualTo("8");
        assertThat(got.get("per group a")).isEqualTo("20");
        assertThat(got.get("per group b")).isEqualTo("7");
        assertThat(got.get("per group c")).isEqualTo("8");
    }

    /** The first value of group c, its order column NULL in one row, the NULLs of o first or last. */
    private static void nullsOrder(StringBuilder sql, DuckDbDialect dialect, String label, NullsOrder nulls) {
        String agg = nth(dialect, false, 1,
                List.of(new OrderedColumn("o", null, Optional.of(SortDirection.ASC), Optional.of(nulls))));
        sql.append(" SELECT '").append(label).append("', ").append(shown(agg)).append(" FROM t WHERE g = 'c';");
    }

    private static String nth(DuckDbDialect dialect, boolean ignoreNulls, Integer n, List<OrderedColumn> columns) {
        return dialect.generateNthValueAgg("v", ignoreNulls, n, columns).orElseThrow();
    }

    /** The CLI writes NULL as an empty field in 1.0 and as NULL later: a NULL is shown as null. */
    private static String shown(String agg) {
        return "COALESCE(CAST(" + agg + " AS VARCHAR), 'null')";
    }
}
