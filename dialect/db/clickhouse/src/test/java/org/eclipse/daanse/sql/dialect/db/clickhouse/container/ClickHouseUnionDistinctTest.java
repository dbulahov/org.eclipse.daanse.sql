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
import org.eclipse.daanse.sql.dialect.db.clickhouse.ClickHouseDialect;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.clickhouse.ClickHouseContainer;

/**
 * The duplicate-eliminating UNION as the dialect spells it. ClickHouse refuses a bare {@code UNION} while the
 * server's {@code union_default_mode} is empty, as it is by default: Code 558, Expected ALL or DISTINCT. The dialect
 * is built from a live connection, and the union it writes must run and leave each row once, also over a column
 * with NULLs and with three inputs. An ORDER BY after the last input orders that input only, not the union (EXPLAIN
 * SYNTAX puts it into the last SELECT): the union is ordered from an outer SELECT. {@code UNION DISTINCT} comes with
 * 21.1; the images of {@code clickhouse/clickhouse-server} begin with 21.3.
 */
@EnabledIfSystemProperty(named = "integration.docker", matches = "true")
class ClickHouseUnionDistinctTest {

    /** a: 1, 2, 2, NULL; b: 2, 3, NULL. */
    private static final List<String> SETUP = List.of(
            "CREATE TABLE a(x Nullable(Int32)) ENGINE = MergeTree() ORDER BY tuple()",
            "CREATE TABLE b(x Nullable(Int32)) ENGINE = MergeTree() ORDER BY tuple()",
            "INSERT INTO a VALUES (1), (2), (2), (NULL)",
            "INSERT INTO b VALUES (2), (3), (NULL)");

    @ParameterizedTest(name = "clickhouse:{0}")
    @ValueSource(strings = { "21.3", "21.8", "23.8", "24.8", "25.8", "26.6" })
    void union_distinct(String tag) throws Exception {
        try (ClickHouseContainer ch = ClickHouseContainers.container(tag)) {
            ch.start();
            try (Connection c = DriverManager.getConnection(ClickHouseContainers.jdbcUrl(ch, tag), ch.getUsername(),
                    ch.getPassword()); Statement st = c.createStatement()) {
                for (String sql : SETUP) {
                    st.execute(sql);
                }
                ClickHouseDialect dialect = new ClickHouseDialect(DialectInitData.fromConnection(c));
                String union = " " + dialect.unionDistinctKeyword() + " ";

                assertThatThrownBy(() -> st.executeQuery("SELECT x FROM a UNION SELECT x FROM b"))
                        .as("the bare UNION the dialect avoids").isInstanceOf(SQLException.class);

                assertThat(values(st, "SELECT x FROM (SELECT x FROM a" + union + "SELECT x FROM b) ORDER BY x"))
                        .as("each row once, one NULL").containsExactly("1", "2", "3", null);
                assertThat(values(st, "SELECT x FROM (SELECT x FROM a" + union + "SELECT x FROM b" + union
                        + "SELECT toNullable(toInt32(4))) ORDER BY x"))
                        .as("three inputs").containsExactly("1", "2", "3", "4", null);
                assertThat(values(st, "SELECT x FROM (SELECT x FROM a WHERE x = 2" + union
                        + "SELECT x FROM a WHERE x = 2)"))
                        .as("duplicates within one input").containsExactly("2");
            }
        }
    }

    private static List<String> values(Statement st, String sql) throws SQLException {
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
