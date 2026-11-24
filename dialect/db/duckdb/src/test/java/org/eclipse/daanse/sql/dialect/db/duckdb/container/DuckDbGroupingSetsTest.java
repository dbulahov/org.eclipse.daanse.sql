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

import java.util.List;

import org.eclipse.daanse.sql.dialect.db.duckdb.DuckDbDialect;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * DuckDB has {@code GROUPING SETS}, {@code ROLLUP}, {@code CUBE} and {@code GROUPING()} in every version from 1.0 on.
 * DuckDB is embedded and serves no connection: the CLI of each version runs the statements in Docker, and the dialect
 * is built for the version the CLI reports. On every version {@code supportsGroupingSets()} says yes, and the
 * statements give a row per grouping set, its total and its {@code GROUPING()} bits.
 */
@EnabledIfSystemProperty(named = "integration.docker", matches = "true")
class DuckDbGroupingSetsTest {

    private static final String SETUP = "CREATE TABLE sales(region VARCHAR, product VARCHAR, amount INT);"
            + " INSERT INTO sales VALUES ('EU', 'A', 10), ('EU', 'B', 20), ('US', 'A', 30);";

    /** The CLI writes NULL as an empty field in 1.0 and as NULL later: a total's key is written as *. */
    private static final String KEYS = "COALESCE(region, '*'), COALESCE(product, '*')";

    @ParameterizedTest(name = "duckdb:{0}")
    @ValueSource(strings = { "1.0.0", "1.1.3", "1.2.2", "1.3.2", "1.4.4", "1.5.6", "2.0.1" })
    void groupingSets(String tag) {
        DuckDbDialect dialect = DuckDbCli.dialect(tag);

        assertThat(dialect.supportsGroupingSets()).as("supportsGroupingSets").isTrue();

        String grouping = dialect.functionGenerator().generateGrouping("region") + ", "
                + dialect.functionGenerator().generateGrouping("product");
        assertThat(rows(tag, SETUP + " SELECT " + KEYS + ", SUM(amount), " + grouping + " FROM sales"
                + " GROUP BY GROUPING SETS ((region, product), (region), ()) ORDER BY 1, 2;"))
                .as("GROUPING SETS, with GROUPING()").containsExactly("*|*|60|1|1", "EU|*|30|0|1", "EU|A|10|0|0",
                        "EU|B|20|0|0", "US|*|30|0|1", "US|A|30|0|0");

        assertThat(rows(tag, SETUP + " SELECT " + KEYS + ", SUM(amount) FROM sales"
                + " GROUP BY ROLLUP (region, product) ORDER BY 1, 2;"))
                .as("ROLLUP").containsExactly("*|*|60", "EU|*|30", "EU|A|10", "EU|B|20", "US|*|30", "US|A|30");

        assertThat(rows(tag, SETUP + " SELECT " + KEYS + ", SUM(amount) FROM sales"
                + " GROUP BY CUBE (region, product) ORDER BY 1, 2;"))
                .as("CUBE, a total for each product too").containsExactly("*|*|60", "*|A|40", "*|B|20", "EU|*|30",
                        "EU|A|10", "EU|B|20", "US|*|30", "US|A|30");
    }

    private static List<String> rows(String tag, String sql) {
        DuckDbCli.Result r = DuckDbCli.run(tag, sql);
        assertThat(r.failed()).as(r.err()).isFalse();
        return r.out();
    }
}
