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
 * SQLite gives the n-th value of a group as the n-th element of {@code json_group_array(v ORDER BY …)} from 3.44.0,
 * where an aggregate takes an ORDER BY: an aggregate over the rows of the group, not the window {@code nth_value …
 * OVER} (V-82). SQLite is embedded: the CLI of each version runs the statements in Docker, and the dialect is built for
 * the version the CLI reports. From 3.44 on the n-th value must follow the order asked for, ascending, descending,
 * NULLs of an order column first or last, a column named with its table; count the NULL values or leave them out as
 * asked; n below 1 is 1; past the end it is NULL; a text keeps its quotes; and it is per group. Before, the dialect
 * offers none.
 */
@EnabledIfSystemProperty(named = "integration.docker", matches = "true")
class SqliteNthValueTest {

    /** Group a in the order of o: NULL, 20, 30, NULL, 50; group b: 7; group c: a NULL o of 9, then 8. */
    private static final String SETUP = "CREATE TABLE t(g TEXT, o INTEGER, v INTEGER, s TEXT);"
            + " INSERT INTO t VALUES ('a', 3, 30, 'it''s'), ('a', 1, NULL, NULL), ('a', 5, 50, 'x'),"
            + " ('a', 2, 20, 'y'), ('a', 4, NULL, NULL), ('b', 1, 7, 'z'), ('c', NULL, 9, NULL), ('c', 1, 8, NULL);\n";

    private static final List<OrderedColumn> BY_O = List.of(new OrderedColumn("o", null, SortDirection.ASC));
    private static final List<OrderedColumn> BY_O_DESC = List.of(new OrderedColumn("o", null, SortDirection.DESC));

    @ParameterizedTest(name = "sqlite {0} -> nth value {1}")
    @CsvSource({ "3.22.0, false", "3.43.1, false", "3.44.0, true", "3.46.1, true", "3.51.0, true" })
    void nth_value(String version, boolean expected) {
        SqliteDialect d = SqliteCli.dialect(version);
        assertThat(d.supportsNthValue()).as("supportsNthValue").isEqualTo(expected);
        assertThat(d.supportsNthValueIgnoreNulls()).as("supportsNthValueIgnoreNulls").isEqualTo(expected);
        if (!expected) {
            assertThat(d.generateNthValueAgg("v", true, 1, BY_O)).as("none before 3.44").isEmpty();
            SqliteCli.Result refused = SqliteCli.run(version,
                    SETUP + "SELECT json_group_array(v ORDER BY o) FROM t;");
            assertThat(refused.failed()).as("the server takes no ORDER BY in an aggregate before 3.44").isTrue();
            return;
        }

        Map<String, String> nth = new LinkedHashMap<>();
        nth.put("1st", nth(d, false, 1, BY_O));
        nth.put("1st ignoring NULLs", nth(d, true, 1, BY_O));
        nth.put("2nd", nth(d, false, 2, BY_O));
        nth.put("2nd ignoring NULLs", nth(d, true, 2, BY_O));
        nth.put("5th", nth(d, false, 5, BY_O));
        nth.put("3rd ignoring NULLs", nth(d, true, 3, BY_O));
        nth.put("4th ignoring NULLs, past the end", nth(d, true, 4, BY_O));
        nth.put("6th, past the end", nth(d, false, 6, BY_O));
        nth.put("0th is the 1st", nth(d, true, 0, BY_O));
        nth.put("no n is the 1st", nth(d, true, null, BY_O));
        nth.put("1st descending", nth(d, false, 1, BY_O_DESC));
        nth.put("2nd descending ignoring NULLs", nth(d, true, 2, BY_O_DESC));
        nth.put("1st by a column of the table", nth(d, true, 1, List.of(new OrderedColumn("o", "t",
                SortDirection.ASC))));
        nth.put("a text with a quote", d.generateNthValueAgg("s", true, 2, BY_O).orElseThrow());

        StringBuilder sql = new StringBuilder(SETUP);
        nth.forEach((label, agg) -> sql.append("SELECT '").append(label).append("|' || ifnull(").append(agg)
                .append(", 'null') FROM t WHERE g = 'a';\n"));
        nullsOrder(sql, d, "NULLS FIRST", NullsOrder.FIRST);
        nullsOrder(sql, d, "NULLS LAST", NullsOrder.LAST);
        sql.append("SELECT 'per group|' || group_concat(g || '=' || ifnull(x, 'null'), ' ') FROM (SELECT g, ")
                .append(nth(d, true, 1, BY_O)).append(" AS x FROM t GROUP BY g ORDER BY g);\n");

        SqliteCli.Result r = SqliteCli.run(version, sql.toString());
        assertThat(r.failed()).as("every n-th value runs: " + r.err() + " in " + nth).isFalse();
        Map<String, String> got = new LinkedHashMap<>();
        for (String line : r.out()) {
            int bar = line.indexOf('|');
            if (bar > 0) {
                got.put(line.substring(0, bar), line.substring(bar + 1));
            }
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
        assertThat(got.get("a text with a quote")).as("the 2nd text, by o: y, then it's").isEqualTo("it's");
        assertThat(got.get("NULLS FIRST")).as("the row of a NULL order first").isEqualTo("9");
        assertThat(got.get("NULLS LAST")).as("the row of a NULL order last").isEqualTo("8");
        assertThat(got.get("per group")).as("per group; c by SQLite's own order, its NULL o first ascending")
                .isEqualTo("a=20 b=7 c=9");
    }

    private static String nth(SqliteDialect d, boolean ignoreNulls, Integer n, List<OrderedColumn> columns) {
        return d.generateNthValueAgg("v", ignoreNulls, n, columns).orElseThrow();
    }

    private static void nullsOrder(StringBuilder sql, SqliteDialect d, String label, NullsOrder nulls) {
        String agg = nth(d, false, 1, List.of(new OrderedColumn("o", null, Optional.of(SortDirection.ASC),
                Optional.of(nulls))));
        sql.append("SELECT '").append(label).append("|' || ifnull(").append(agg)
                .append(", 'null') FROM t WHERE g = 'c';\n");
    }
}
