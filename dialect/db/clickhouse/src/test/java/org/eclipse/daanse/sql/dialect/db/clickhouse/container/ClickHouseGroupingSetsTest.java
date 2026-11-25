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
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.daanse.sql.dialect.api.DialectInitData;
import org.eclipse.daanse.sql.dialect.db.clickhouse.ClickHouseDialect;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.testcontainers.clickhouse.ClickHouseContainer;

/**
 * ClickHouse has {@code GROUP BY GROUPING SETS} from 22.6, but 22.6 leaves its rows unsorted by an {@code ORDER BY},
 * and 22.6 to 22.8 give {@code GROUPING()} the other way round; 22.9 gives it as the standard does. The dialect is
 * built from a live connection: from 22.9 on {@code supportsGroupingSets()} says yes, and the statements give a row
 * per grouping set, its total and its {@code GROUPING()} bits, with the dialect's {@code GROUPING()}, and ROLLUP and
 * CUBE too; before, it says no. A total's key of a column that is not Nullable is the type's default, not NULL,
 * unless {@code group_by_use_nulls = 1}, under which 24.8 and 25.8 refuse {@code GROUPING()}.
 */
@EnabledIfSystemProperty(named = "integration.docker", matches = "true")
class ClickHouseGroupingSetsTest {

    private static final List<String> SETUP = List.of(
            "CREATE TABLE sales(region Nullable(String), product Nullable(String), amount Int32)"
                    + " ENGINE = MergeTree() ORDER BY tuple()",
            "INSERT INTO sales VALUES ('EU', 'A', 10), ('EU', 'B', 20), ('US', 'A', 30)",
            "CREATE TABLE plain(region String, amount Int32) ENGINE = MergeTree() ORDER BY tuple()",
            "INSERT INTO plain VALUES ('EU', 10), ('US', 30)");

    /** A total's key as *. */
    private static final String KEYS = "ifNull(region, '*'), ifNull(product, '*')";

    @ParameterizedTest(name = "clickhouse:{0} -> grouping sets {1}")
    @CsvSource({ "21.8, false", "22.3, false", "22.6, false", "22.8, false", "22.9, true", "23.8, true",
            "24.8, true", "25.8, true", "26.6, true" })
    void grouping_sets(String tag, boolean expected) throws Exception {
        try (ClickHouseContainer ch = ClickHouseContainers.container(tag)) {
            ch.start();
            try (Connection c = DriverManager.getConnection(ClickHouseContainers.jdbcUrl(ch, tag), ch.getUsername(),
                    ch.getPassword()); Statement st = c.createStatement()) {
                for (String sql : SETUP) {
                    st.execute(sql);
                }
                ClickHouseDialect dialect = new ClickHouseDialect(DialectInitData.fromConnection(c));
                assertThat(dialect.supportsGroupingSets()).as("supportsGroupingSets").isEqualTo(expected);

                String grouping = dialect.functionGenerator().generateGrouping("region") + ", "
                        + dialect.functionGenerator().generateGrouping("product");
                String sets = "SELECT " + KEYS + ", sum(amount), " + grouping + " FROM sales"
                        + " GROUP BY GROUPING SETS ((region, product), (region), ()) ORDER BY 1, 2";
                if (!expected) {
                    if (tag.startsWith("21.") || tag.equals("22.3")) {
                        assertThatThrownBy(() -> st.executeQuery(sets)).as("no GROUPING SETS before 22.6")
                                .isInstanceOf(SQLException.class).hasMessageContaining("Syntax error");
                    } else {
                        assertThat(rows(st, sets)).as("22.6 to 22.8 run it, not as the standard does")
                                .isNotEqualTo(STANDARD);
                    }
                    return;
                }

                assertThat(rows(st, sets)).as("GROUPING SETS, with GROUPING()").containsExactlyElementsOf(STANDARD);
                assertThat(rows(st, "SELECT " + KEYS + ", sum(amount) FROM sales GROUP BY ROLLUP(region, product)"
                        + " ORDER BY 1, 2")).as("ROLLUP")
                        .containsExactly("*|*|60", "EU|*|30", "EU|A|10", "EU|B|20", "US|*|30", "US|A|30");
                assertThat(rows(st, "SELECT " + KEYS + ", sum(amount) FROM sales GROUP BY CUBE(region, product)"
                        + " ORDER BY 1, 2")).as("CUBE, a total for each product too").containsExactly("*|*|60",
                                "*|A|40", "*|B|20", "EU|*|30", "EU|A|10", "EU|B|20", "US|*|30", "US|A|30");

                String plain = "SELECT region, sum(amount), " + dialect.functionGenerator().generateGrouping("region")
                        + " FROM plain GROUP BY GROUPING SETS ((region), ()) ORDER BY 3, 1";
                assertThat(rows(st, plain)).as("a total's key of a column not Nullable: '', told by GROUPING()")
                        .containsExactly("EU|10|0", "US|30|0", "|40|1");
                // without GROUPING(): 24.8 and 25.8 refuse it under group_by_use_nulls (BAD_ARGUMENTS)
                assertThat(rows(st, "SELECT region, sum(amount) FROM plain GROUP BY GROUPING SETS ((region), ())"
                        + " ORDER BY 1 NULLS LAST SETTINGS group_by_use_nulls = 1")).as("NULL with group_by_use_nulls")
                        .containsExactly("EU|10", "US|30", "null|40");
            }
        }
    }

    /** Each grouping set's rows, sorted, with the GROUPING() bits of the standard. */
    private static final List<String> STANDARD = List.of("*|*|60|1|1", "EU|*|30|0|1", "EU|A|10|0|0", "EU|B|20|0|0",
            "US|*|30|0|1", "US|A|30|0|0");

    private static List<String> rows(Statement st, String sql) throws SQLException {
        List<String> out = new ArrayList<>();
        try (ResultSet rs = st.executeQuery(sql)) {
            ResultSetMetaData md = rs.getMetaData();
            while (rs.next()) {
                List<String> row = new ArrayList<>();
                for (int i = 1; i <= md.getColumnCount(); i++) {
                    row.add(String.valueOf(rs.getString(i)));
                }
                out.add(String.join("|", row));
            }
        } catch (SQLException e) {
            throw new SQLException(e.getMessage() + " in " + sql, e);
        }
        return out;
    }
}
