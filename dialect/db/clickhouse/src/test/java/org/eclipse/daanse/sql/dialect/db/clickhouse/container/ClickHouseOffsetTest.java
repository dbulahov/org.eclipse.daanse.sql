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
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;

import org.eclipse.daanse.sql.dialect.api.DialectInitData;
import org.eclipse.daanse.sql.dialect.api.generator.PaginationGenerator;
import org.eclipse.daanse.sql.dialect.api.generator.PaginationGenerator.PagingParam;
import org.eclipse.daanse.sql.dialect.api.generator.PaginationGenerator.PreparedPaging;
import org.eclipse.daanse.sql.dialect.db.clickhouse.ClickHouseDialect;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.clickhouse.ClickHouseContainer;

/**
 * ClickHouse skips and limits rows with {@code LIMIT n OFFSET m}, {@code LIMIT n} and {@code OFFSET m} on every
 * version, with an ORDER BY or without; the standard {@code OFFSET m ROWS FETCH NEXT n ROWS ONLY} it refuses without
 * an ORDER BY, on every version. The dialect is built from a live connection, and the paging it writes must skip and
 * limit the rows of a sorted query and count them in one that is not, limit to no row, skip past the end, and run
 * with its parameters bound in the order it gives.
 */
@EnabledIfSystemProperty(named = "integration.docker", matches = "true")
class ClickHouseOffsetTest {

    private static final String SORTED = "SELECT toString(x) FROM t ORDER BY x";
    private static final String UNSORTED = "SELECT x FROM t";

    @ParameterizedTest(name = "clickhouse:{0}")
    @ValueSource(strings = { "21.3", "21.8", "23.8", "24.8", "25.8", "26.6" })
    void offset(String tag) throws Exception {
        try (ClickHouseContainer ch = ClickHouseContainers.container(tag)) {
            ch.start();
            try (Connection c = DriverManager.getConnection(ClickHouseContainers.jdbcUrl(ch, tag), ch.getUsername(),
                    ch.getPassword()); Statement st = c.createStatement()) {
                st.execute("CREATE TABLE t(x Int32) ENGINE = MergeTree() ORDER BY tuple()");
                st.execute("INSERT INTO t SELECT toInt32(number) FROM numbers(10)");
                PaginationGenerator p = new ClickHouseDialect(DialectInitData.fromConnection(c))
                        .paginationGenerator();
                assertThat(p.supportsOffset()).as("supportsOffset").isTrue();
                assertThat(p.selectPrefix(OptionalLong.of(3), OptionalLong.of(2))).as("a trailing clause").isEmpty();

                assertThat(rows(st, SORTED + paging(p, 3, 2))).as("skip 2, take 3").containsExactly("2", "3", "4");
                assertThat(rows(st, SORTED + paging(p, 3, null))).as("take 3").containsExactly("0", "1", "2");
                assertThat(rows(st, SORTED + paging(p, null, 7))).as("skip 7").containsExactly("7", "8", "9");
                assertThat(rows(st, SORTED + paging(p, 0, null))).as("take none").isEmpty();
                assertThat(rows(st, SORTED + paging(p, 3, 9))).as("fewer left").containsExactly("9");
                assertThat(rows(st, SORTED + paging(p, 3, 20))).as("skip past the end").isEmpty();
                assertThat(rows(st, SORTED + paging(p, null, null))).as("no paging").hasSize(10);

                assertThat(count(st, UNSORTED + paging(p, 3, 2))).as("without ORDER BY: skip and take").isEqualTo(3);
                assertThat(count(st, UNSORTED + paging(p, null, 7))).as("without ORDER BY: skip").isEqualTo(3);
                assertThat(count(st, UNSORTED + paging(p, 4, null))).as("without ORDER BY: take").isEqualTo(4);
                assertThatThrownBy(() -> rows(st, UNSORTED + " OFFSET 2 ROWS FETCH NEXT 3 ROWS ONLY"))
                        .as("the standard form without ORDER BY, which the dialect does not write")
                        .isInstanceOf(SQLException.class);

                assertThat(prepared(c, p, 3L, 2L)).as("prepared, skip 2, take 3").containsExactly("2", "3", "4");
                assertThat(prepared(c, p, 3L, null)).as("prepared, take 3").containsExactly("0", "1", "2");
                assertThat(prepared(c, p, null, 7L)).as("prepared, skip 7").containsExactly("7", "8", "9");
            }
        }
    }

    private static String paging(PaginationGenerator p, Integer limit, Integer offset) {
        return p.paginate(limit == null ? OptionalLong.empty() : OptionalLong.of(limit),
                offset == null ? OptionalLong.empty() : OptionalLong.of(offset));
    }

    /** The prepared form, its parameters bound in the order it gives. */
    private static List<String> prepared(Connection c, PaginationGenerator p, Long limit, Long offset)
            throws SQLException {
        PreparedPaging paging = p.paginatePrepared(limit != null, offset != null);
        String sql = SORTED + " " + paging.clause();
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            int i = 1;
            for (PagingParam param : paging.order()) {
                ps.setLong(i++, param == PagingParam.LIMIT ? limit : offset);
            }
            List<String> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getString(1));
                }
            }
            return out;
        } catch (SQLException e) {
            throw new SQLException(e.getMessage() + " in " + sql, e);
        }
    }

    private static long count(Statement st, String sql) throws SQLException {
        return Long.parseLong(rows(st, "SELECT toString(count()) FROM (" + sql + ")").get(0));
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
