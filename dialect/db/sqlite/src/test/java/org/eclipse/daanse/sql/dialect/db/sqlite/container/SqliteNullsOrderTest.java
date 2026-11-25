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
import java.util.Map;

import org.eclipse.daanse.sql.dialect.db.sqlite.SqliteDialect;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * SQLite has {@code NULLS FIRST} and {@code NULLS LAST} from 3.30.0, and puts NULLs first ascending and last
 * descending by itself. SQLite is embedded: the CLI of each version runs the statements in Docker, and the dialect is
 * built for the version the CLI reports. From 3.30 on the dialect orders with NULLS FIRST/LAST, before with a CASE
 * that ranks the NULLs, which 3.25 runs where it refuses the other; on every version the order must put the NULLs
 * where asked, ascending and descending, through generateOrderItem and generateOrderByNulls alike.
 */
@EnabledIfSystemProperty(named = "integration.docker", matches = "true")
class SqliteNullsOrderTest {

    private static final String SETUP = "CREATE TABLE t(v INTEGER); INSERT INTO t VALUES (2), (NULL), (1), (3);\n";

    @ParameterizedTest(name = "sqlite {0} -> NULLS FIRST/LAST {1}")
    @CsvSource({ "3.22.0, false", "3.25.3, false", "3.32.1, true", "3.40.1, true", "3.46.1, true",
            "3.51.0, true" })
    void nulls_order(String version, boolean nullsOrdering) {
        SqliteDialect d = SqliteCli.dialect(version);
        assertThat(d.supportsNullsLast()).as("supportsNullsLast").isEqualTo(nullsOrdering);
        assertThat(d.getOrderByCapabilities().supportsNullsLast()).as("the capabilities say the same")
                .isEqualTo(nullsOrdering);
        assertThat(d.generateOrderItem("v", true, true, true).toString().contains("NULLS"))
                .as("NULLS written").isEqualTo(nullsOrdering);

        Map<String, String> orders = new LinkedHashMap<>();
        orders.put("asc nulls last", d.generateOrderItem("v", true, true, true).toString());
        orders.put("asc nulls first", d.generateOrderItem("v", true, true, false).toString());
        orders.put("desc nulls last", d.generateOrderItem("v", true, false, true).toString());
        orders.put("desc nulls first", d.generateOrderItem("v", true, false, false).toString());
        orders.put("by nulls, desc nulls last", d.generateOrderByNulls("v", false, true).toString());
        orders.put("not nullable, asc", d.generateOrderItem("v", false, true, true).toString());
        orders.put("not nullable, desc", d.generateOrderItem("v", false, false, true).toString());

        StringBuilder sql = new StringBuilder(SETUP);
        orders.forEach((label, order) -> sql.append("SELECT '").append(label)
                .append("|' || group_concat(ifnull(v, 'null'), ',') FROM (SELECT v FROM t ORDER BY ").append(order)
                .append(");\n"));
        SqliteCli.Result r = SqliteCli.run(version, sql.toString());
        assertThat(r.failed()).as("every order runs: " + r.err() + " in " + orders).isFalse();
        Map<String, String> got = new LinkedHashMap<>();
        for (String line : r.out()) {
            int bar = line.indexOf('|');
            if (bar > 0) {
                got.put(line.substring(0, bar), line.substring(bar + 1));
            }
        }

        assertThat(got.get("asc nulls last")).as(orders.get("asc nulls last")).isEqualTo("1,2,3,null");
        assertThat(got.get("asc nulls first")).as(orders.get("asc nulls first")).isEqualTo("null,1,2,3");
        assertThat(got.get("desc nulls last")).as(orders.get("desc nulls last")).isEqualTo("3,2,1,null");
        assertThat(got.get("desc nulls first")).as(orders.get("desc nulls first")).isEqualTo("null,3,2,1");
        assertThat(got.get("by nulls, desc nulls last")).isEqualTo("3,2,1,null");
        assertThat(got.get("not nullable, asc")).as("SQLite's own: NULLs first ascending").isEqualTo("null,1,2,3");
        assertThat(got.get("not nullable, desc")).as("and last descending").isEqualTo("3,2,1,null");

        SqliteCli.Result ansi = SqliteCli.run(version, SETUP + "SELECT v FROM t ORDER BY v ASC NULLS LAST;");
        assertThat(ansi.failed()).as("the server takes NULLS LAST: " + ansi.err()).isEqualTo(!nullsOrdering);
    }
}
