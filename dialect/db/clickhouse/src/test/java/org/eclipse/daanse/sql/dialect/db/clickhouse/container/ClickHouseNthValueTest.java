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

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.eclipse.daanse.sql.dialect.api.DialectInitData;
import org.eclipse.daanse.sql.dialect.db.clickhouse.ClickHouseDialect;
import org.eclipse.daanse.sql.model.sql.NullsOrder;
import org.eclipse.daanse.sql.model.sql.OrderedColumn;
import org.eclipse.daanse.sql.model.sql.SortDirection;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.clickhouse.ClickHouseContainer;

/**
 * ClickHouse has no ordered array aggregate: the dialect gives the n-th value of a group as the n-th element of the
 * group's rows, collected with {@code groupArray} and sorted by the columns, on every version here: an aggregate over
 * the rows of the group, not the window {@code nth_value … OVER} (V-82). The dialect is built from a live connection,
 * and the n-th value must follow the order asked for, ascending, descending, NULLs of an order column first or last,
 * a column named with its table; it counts the NULL values or leaves them out as asked; n below 1 is 1; past the end
 * it is NULL, not the type's default; and it is per group.
 */
@EnabledIfSystemProperty(named = "integration.docker", matches = "true")
class ClickHouseNthValueTest {

    /** Group a in the order of o: NULL, 20, 30, NULL, 50; group b: 7; group c: a NULL o of 9, then 8. */
    private static final List<String> SETUP = List.of(
            "CREATE TABLE t(g String, o Nullable(Int32), v Nullable(Int32)) ENGINE = MergeTree() ORDER BY tuple()",
            "INSERT INTO t VALUES ('a', 3, 30), ('a', 1, NULL), ('a', 5, 50), ('a', 2, 20), ('a', 4, NULL),"
                    + " ('b', 1, 7), ('c', NULL, 9), ('c', 1, 8)");

    private static final List<OrderedColumn> BY_O = List.of(new OrderedColumn("o", null, SortDirection.ASC));
    private static final List<OrderedColumn> BY_O_DESC = List.of(new OrderedColumn("o", null, SortDirection.DESC));

    @ParameterizedTest(name = "clickhouse:{0}")
    @ValueSource(strings = { "21.8", "23.8", "24.8", "25.8", "26.6" })
    void nth_value(String tag) throws Exception {
        try (ClickHouseContainer ch = ClickHouseContainers.container(tag)) {
            ch.start();
            try (Connection c = DriverManager.getConnection(ClickHouseContainers.jdbcUrl(ch, tag), ch.getUsername(),
                    ch.getPassword()); Statement st = c.createStatement()) {
                for (String sql : SETUP) {
                    st.execute(sql);
                }
                ClickHouseDialect d = new ClickHouseDialect(DialectInitData.fromConnection(c));
                assertThat(d.supportsNthValue()).as("supportsNthValue").isTrue();
                assertThat(d.supportsNthValueIgnoreNulls()).as("supportsNthValueIgnoreNulls").isTrue();

                assertThat(nth(st, d, false, 1, BY_O, "a")).as("the NULLs counted").isNull();
                assertThat(nth(st, d, true, 1, BY_O, "a")).as("1st ignoring NULLs").isEqualTo("20");
                assertThat(nth(st, d, false, 2, BY_O, "a")).as("2nd").isEqualTo("20");
                assertThat(nth(st, d, true, 2, BY_O, "a")).as("2nd ignoring NULLs").isEqualTo("30");
                assertThat(nth(st, d, false, 5, BY_O, "a")).as("5th").isEqualTo("50");
                assertThat(nth(st, d, true, 3, BY_O, "a")).as("3rd ignoring NULLs").isEqualTo("50");
                assertThat(nth(st, d, true, 4, BY_O, "a")).as("4th ignoring NULLs, past the end").isNull();
                assertThat(nth(st, d, false, 6, BY_O, "a")).as("6th, past the end").isNull();
                assertThat(nth(st, d, true, 0, BY_O, "a")).as("0th is the 1st").isEqualTo("20");
                assertThat(nth(st, d, true, null, BY_O, "a")).as("no n is the 1st").isEqualTo("20");
                assertThat(nth(st, d, false, 1, BY_O_DESC, "a")).as("1st descending").isEqualTo("50");
                assertThat(nth(st, d, true, 2, BY_O_DESC, "a")).as("2nd descending ignoring NULLs")
                        .isEqualTo("30");
                assertThat(nth(st, d, true, 1, List.of(new OrderedColumn("o", "t", SortDirection.ASC)), "a"))
                        .as("1st by a column of the table").isEqualTo("20");
                assertThat(nth(st, d, false, 1, List.of(new OrderedColumn("o", null, Optional.of(SortDirection.ASC),
                        Optional.of(NullsOrder.FIRST))), "c")).as("the row of a NULL order first").isEqualTo("9");
                assertThat(nth(st, d, false, 1, List.of(new OrderedColumn("o", null, Optional.of(SortDirection.ASC),
                        Optional.of(NullsOrder.LAST))), "c")).as("the row of a NULL order last").isEqualTo("8");
                assertThat(nth(st, d, false, 1, List.of(new OrderedColumn("o", null, Optional.of(SortDirection.DESC),
                        Optional.of(NullsOrder.FIRST))), "c")).as("descending, NULLs first").isEqualTo("9");
                assertThat(nth(st, d, true, 1, List.of(), "b")).as("no order, one value").isEqualTo("7");

                String perGroup = d.generateNthValueAgg("v", true, 1, BY_O).orElseThrow();
                assertThat(rows(st, "SELECT concat(g, '=', ifNull(toString(" + perGroup + "), 'null')) FROM t"
                        + " GROUP BY g ORDER BY g")).as("per group").containsExactly("a=20", "b=7", "c=8");

                String nonNull = d.generateNthValueAgg("toInt32(ifNull(v, 0))", false, 9, BY_O).orElseThrow();
                assertThat(rows(st, "SELECT toString(" + nonNull + ") FROM t WHERE g = 'a'"))
                        .as("past the end of a column not Nullable: NULL, not 0").containsExactly((String) null);
            }
        }
    }

    private static String nth(Statement st, ClickHouseDialect d, boolean ignoreNulls, Integer n,
            List<OrderedColumn> columns, String group) throws SQLException {
        String agg = d.generateNthValueAgg("v", ignoreNulls, n, columns).orElseThrow();
        List<String> rows = rows(st, "SELECT toString(" + agg + ") FROM t WHERE g = '" + group + "'");
        assertThat(rows).hasSize(1);
        return rows.get(0);
    }

    private static List<String> rows(Statement st, String sql) throws SQLException {
        List<String> out = new ArrayList<>();
        try (ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                out.add(rs.getString(1));
            }
        } catch (SQLException e) {
            throw new SQLException(e.getMessage() + " in " + sql, e);
        }
        return out;
    }
}
