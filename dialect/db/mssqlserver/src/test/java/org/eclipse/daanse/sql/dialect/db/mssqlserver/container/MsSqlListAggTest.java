/*
 * Copyright (c) 2026 Contributors to the Eclipse Foundation.
 *
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.daanse.sql.dialect.db.mssqlserver.container;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import org.eclipse.daanse.sql.dialect.db.mssqlserver.MicrosoftSqlServerDialect;
import org.eclipse.daanse.sql.model.sql.NullsOrder;
import org.eclipse.daanse.sql.model.sql.OrderedColumn;
import org.eclipse.daanse.sql.model.sql.SortDirection;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.containers.MSSQLServerContainer;

/**
 * SQL Server lists the values of a group with {@code STRING_AGG(x, 's') WITHIN GROUP (ORDER BY …)}, from 2017 on, so
 * in every version Docker serves. The dialect is built from a live connection, and the list it writes must come out of
 * the server in the order asked for, without the NULL values, with any separator, a quote too, of a column of any
 * type, of Unicode text, longer than 8000 bytes, and per group. SQL Server knows no {@code NULLS FIRST/LAST}: the
 * dialect ranks the NULLs of an order column with a CASE. It knows no {@code STRING_AGG(DISTINCT …)} either, and the
 * dialect writes none. The lists are read per group: SQL Server 2017 to 2022 ordered a list over the rows of a
 * {@code WHERE} without {@code GROUP BY} wrongly at times, a fault of its plan.
 */
@EnabledIfSystemProperty(named = "integration.docker", matches = "true")
class MsSqlListAggTest {

    /** Group a: alice 20, bob 30, bob 10, a NULL name of 40; group b: dave 5. */
    private static final List<String> SETUP = List.of(
            "CREATE TABLE emp(dept VARCHAR(1), name VARCHAR(10), salary INT, uname NVARCHAR(10))",
            "INSERT INTO emp VALUES ('a', 'bob', 30, N'ö'), ('a', NULL, 40, NULL), ('a', 'alice', 20, N'日本'),"
                    + " ('a', 'bob', 10, N'ä'), ('b', 'dave', 5, N'x')",
            "CREATE TABLE big(s VARCHAR(100))",
            "INSERT INTO big SELECT TOP 200 REPLICATE('x', 100) FROM sys.all_objects");

    @ParameterizedTest(name = "mssql/server:{0}")
    @ValueSource(strings = { "2017-latest", "2019-latest", "2022-latest", "2025-latest" })
    void listAgg(String tag) throws Exception {
        try (MSSQLServerContainer<?> ms = new MSSQLServerContainer<>("mcr.microsoft.com/mssql/server:" + tag)
                .acceptLicense()) {
            ms.start();
            try (Connection c = DriverManager.getConnection(ms.getJdbcUrl(), ms.getUsername(), ms.getPassword());
                    Statement st = c.createStatement()) {
                for (String sql : SETUP) {
                    st.execute(sql);
                }
                MicrosoftSqlServerDialect dialect = new MicrosoftSqlServerDialect(DialectInitData.fromConnection(c));
                assertThat(dialect.supportsListAgg()).isTrue();

                assertThat(list(st, dialect, "name", ", ", order("name", SortDirection.ASC)))
                        .as("ordered by the value, the NULL left out").isEqualTo("alice, bob, bob");
                assertThat(list(st, dialect, "name", ", ", order("salary", SortDirection.DESC)))
                        .as("ordered by another column").isEqualTo("bob, alice, bob");
                assertThat(list(st, dialect, "name", ", ",
                        List.of(new OrderedColumn("name", "emp", SortDirection.ASC))))
                        .as("a column of the table").isEqualTo("alice, bob, bob");
                assertThat(Arrays.stream(list(st, dialect, "name", ",", List.of()).split(",")).sorted())
                        .as("without an order").containsExactly("alice", "bob", "bob");

                assertThat(list(st, dialect, "name", null, order("name", SortDirection.ASC)))
                        .as("no separator is a comma and a space").isEqualTo("alice, bob, bob");
                assertThat(list(st, dialect, "name", "'", order("name", SortDirection.ASC)))
                        .as("a quote as separator").isEqualTo("alice'bob'bob");
                assertThat(list(st, dialect, "name", "\\", order("name", SortDirection.ASC)))
                        .as("a backslash as separator").isEqualTo("alice\\bob\\bob");
                assertThat(list(st, dialect, "name", " · ", order("name", SortDirection.ASC)))
                        .as("a separator out of ASCII").isEqualTo("alice · bob · bob");

                assertThat(list(st, dialect, "salary", ",", order("salary", SortDirection.ASC)))
                        .as("an INT column").isEqualTo("10,20,30,40");
                assertThat(list(st, dialect, "uname", ",", order("salary", SortDirection.ASC)))
                        .as("Unicode text").isEqualTo("ä,日本,ö");
                assertThat(list(st, dialect, "salary", ",",
                        List.of(new OrderedColumn("name", null, Optional.of(SortDirection.ASC),
                                Optional.of(NullsOrder.FIRST)), new OrderedColumn("salary", null, SortDirection.ASC))))
                        .as("NULLS FIRST, then a second column").isEqualTo("40,20,10,30");
                assertThat(list(st, dialect, "salary", ",",
                        List.of(new OrderedColumn("name", null, Optional.of(SortDirection.ASC),
                                Optional.of(NullsOrder.LAST)), new OrderedColumn("salary", null, SortDirection.ASC))))
                        .as("NULLS LAST, then a second column").isEqualTo("20,10,30,40");
                assertThat(list(st, dialect, "salary", ",",
                        List.of(new OrderedColumn("name", null, Optional.of(SortDirection.DESC),
                                Optional.of(NullsOrder.FIRST)), new OrderedColumn("salary", null, SortDirection.ASC))))
                        .as("descending, NULLS FIRST").isEqualTo("40,10,30,20");

                String longList = dialect.generateListAgg("s", false, ",", null, null, List.of()).orElseThrow();
                assertThat(value(st, "SELECT LEN(" + longList + ") FROM big")).as("longer than 8000 bytes")
                        .isEqualTo("20199");
                assertThatThrownBy(() -> value(st, "SELECT LEN(STRING_AGG(s, ',')) FROM big"))
                        .as("the operand left as it is stops at 8000 bytes").isInstanceOf(SQLException.class)
                        .hasMessageContaining("8000 bytes");

                String perDept = dialect.generateListAgg("name", false, ",", null, null,
                        order("name", SortDirection.ASC)).orElseThrow();
                assertThat(rows(st, "SELECT dept, " + perDept + " FROM emp GROUP BY dept ORDER BY dept"))
                        .as("per group").containsExactly("a=alice,bob,bob", "b=dave");

                assertThat(dialect.generateListAgg("name", true, ",", null, null, List.of()))
                        .as("no DISTINCT list").isEmpty();
                assertThatThrownBy(() -> value(st, "SELECT STRING_AGG(DISTINCT name, ',') FROM emp"))
                        .as("SQL Server knows no STRING_AGG(DISTINCT …)").isInstanceOf(SQLException.class);
            }
        }
    }

    private static List<OrderedColumn> order(String column, SortDirection direction) {
        return List.of(new OrderedColumn(column, null, direction));
    }

    private static String list(Statement st, MicrosoftSqlServerDialect dialect, String operand, String separator,
            List<OrderedColumn> columns) throws SQLException {
        String agg = dialect.generateListAgg(operand, false, separator, null, null, columns).orElseThrow();
        try {
            // per group, as the engine lists: a list over all rows of a WHERE alone came out in the wrong order on 2017
            // to 2022 (ORDER BY salary DESC gave 10,20,30,40), though not with GROUP BY or OPTION (RECOMPILE), nor on
            // 2025, a fault of the server's plan the dialect cannot write around
            return value(st, "SELECT " + agg + " FROM emp WHERE dept = 'a' GROUP BY dept");
        } catch (SQLException e) {
            throw new SQLException(e.getMessage() + " in " + agg, e);
        }
    }

    private static String value(Statement st, String sql) throws SQLException {
        try (ResultSet rs = st.executeQuery(sql)) {
            assertThat(rs.next()).isTrue();
            return rs.getString(1);
        }
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
