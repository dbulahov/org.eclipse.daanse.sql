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
import static org.assertj.core.api.Assertions.within;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.daanse.sql.dialect.api.DialectInitData;
import org.eclipse.daanse.sql.dialect.db.clickhouse.ClickHouseDialect;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.clickhouse.ClickHouseContainer;

/**
 * ClickHouse has no {@code PERCENTILE_DISC} and no {@code PERCENTILE_CONT … WITHIN GROUP}, and none of its quantiles
 * gives the discrete one; {@code quantileExactInclusive}, the continuous one, gives the 2nd value for p = 1 on 21.x.
 * The dialect computes both on the sorted values of the group, on every version. Built from a live connection, its
 * percentiles must give what the standard says: the discrete one a value of the group, the continuous one interpolated,
 * ascending and descending, the NULLs left out, p at its ends, per group, and NULL for a group of no value.
 */
@EnabledIfSystemProperty(named = "integration.docker", matches = "true")
class ClickHousePercentileTest {

    /** Group a: 10, 20, 30, 40 and a NULL that every aggregate leaves out; group b: 5; group c: only a NULL. */
    private static final List<String> SETUP = List.of(
            "CREATE TABLE nums(g String, v Nullable(Int32)) ENGINE = MergeTree() ORDER BY tuple()",
            "INSERT INTO nums VALUES ('a', 30), ('a', 10), ('a', NULL), ('a', 40), ('a', 20), ('b', 5), ('c', NULL)");

    @ParameterizedTest(name = "clickhouse:{0}")
    @ValueSource(strings = { "21.3", "21.8", "23.8", "24.8", "25.8", "26.6" })
    void percentile(String tag) throws Exception {
        try (ClickHouseContainer ch = ClickHouseContainers.container(tag)) {
            ch.start();
            try (Connection c = DriverManager.getConnection(ClickHouseContainers.jdbcUrl(ch, tag), ch.getUsername(),
                    ch.getPassword()); Statement st = c.createStatement()) {
                for (String sql : SETUP) {
                    st.execute(sql);
                }
                ClickHouseDialect d = new ClickHouseDialect(DialectInitData.fromConnection(c));
                assertThat(d.supportsPercentileDisc()).as("supportsPercentileDisc").isTrue();
                assertThat(d.supportsPercentileCont()).as("supportsPercentileCont").isTrue();
                assertThatThrownBy(() -> rows(st, "SELECT percentile_cont(0.5) WITHIN GROUP (ORDER BY v) FROM nums"))
                        .as("the server knows no WITHIN GROUP").isInstanceOf(SQLException.class);

                assertThat(disc(st, d, 0.5, false, "nums")).as("disc 0.5").isEqualTo(20);
                assertThat(disc(st, d, 0.25, false, "nums")).as("disc 0.25: cume_dist of 10 is 0.25").isEqualTo(10);
                assertThat(disc(st, d, 0.7, false, null)).as("disc 0.7, the column alone").isEqualTo(30);
                assertThat(disc(st, d, 0, false, "nums")).as("disc 0, the least").isEqualTo(10);
                assertThat(disc(st, d, 1, false, "nums")).as("disc 1, the greatest").isEqualTo(40);
                assertThat(disc(st, d, 0.5, true, "nums")).as("disc 0.5 descending").isEqualTo(30);
                assertThat(disc(st, d, 0.25, true, null)).as("disc 0.25 descending").isEqualTo(40);

                assertThat(cont(st, d, 0.5, false, "nums")).as("cont 0.5").isEqualTo(25);
                assertThat(cont(st, d, 0.25, false, null)).as("cont 0.25, the column alone").isEqualTo(17.5);
                assertThat(cont(st, d, 0.7, false, "nums")).as("cont 0.7").isCloseTo(31, within(1e-9));
                assertThat(cont(st, d, 0, false, "nums")).as("cont 0, the least").isEqualTo(10);
                assertThat(cont(st, d, 1, false, "nums")).as("cont 1, the greatest, which 21.x's own gets wrong")
                        .isEqualTo(40);
                assertThat(cont(st, d, 0.25, true, "nums")).as("cont 0.25 descending").isEqualTo(32.5);
                assertThat(cont(st, d, 1, true, "nums")).as("descending, the end: the least").isEqualTo(10);

                String perGroup = "SELECT concat(g, '=', ifNull(toString(%s), 'null')) FROM nums GROUP BY g ORDER BY g";
                assertThat(rows(st, perGroup.formatted(d.generatePercentileCont(0.5, false, null, "v").orElseThrow())))
                        .as("cont per group, one value, no value").containsExactly("a=25", "b=5", "c=null");
                assertThat(rows(st, perGroup.formatted(d.generatePercentileDisc(0.5, false, null, "v").orElseThrow())))
                        .as("disc per group, one value, no value").containsExactly("a=20", "b=5", "c=null");
            }
        }
    }

    private static double disc(Statement st, ClickHouseDialect d, double p, boolean desc, String table)
            throws SQLException {
        return value(st, d.generatePercentileDisc(p, desc, table, "v").orElseThrow());
    }

    private static double cont(Statement st, ClickHouseDialect d, double p, boolean desc, String table)
            throws SQLException {
        return value(st, d.generatePercentileCont(p, desc, table, "v").orElseThrow());
    }

    /** The percentile of group a. */
    private static double value(Statement st, String percentile) throws SQLException {
        List<String> rows = rows(st, "SELECT toString(" + percentile + ") FROM nums WHERE g = 'a'");
        assertThat(rows).as(percentile).hasSize(1);
        return Double.parseDouble(rows.get(0));
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
