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
import java.util.Map;

import org.eclipse.daanse.sql.dialect.db.duckdb.DuckDbDialect;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * DuckDB has {@code PERCENTILE_DISC} and {@code PERCENTILE_CONT … WITHIN GROUP (ORDER BY …)}, in every version from 1.0
 * on. The CLI of each version runs the percentiles the dialect writes, in Docker, and the dialect is built for the
 * version the CLI reports: each must give the right value, ascending and descending, of a column named with its table
 * or without, at the ends of the range too, leaving out the NULL, and per group.
 */
@EnabledIfSystemProperty(named = "integration.docker", matches = "true")
class DuckDbPercentileTest {

    /** 10, 20, 30, 40 and a NULL that every aggregate leaves out in group a; 5 alone in group b. */
    private static final String SETUP = "CREATE TABLE nums(g VARCHAR, v INT);"
            + " INSERT INTO nums VALUES ('a', 30), ('a', 10), ('a', NULL), ('a', 40), ('a', 20), ('b', 5);";

    @ParameterizedTest(name = "duckdb:{0}")
    @ValueSource(strings = { "1.0.0", "1.1.3", "1.2.2", "1.3.2", "1.4.4", "1.5.6", "2.0.1" })
    void percentile(String tag) {
        DuckDbDialect dialect = DuckDbCli.dialect(tag);
        assertThat(dialect.supportsPercentileDisc()).as("supportsPercentileDisc").isTrue();
        assertThat(dialect.supportsPercentileCont()).as("supportsPercentileCont").isTrue();

        Map<String, String> percentiles = new LinkedHashMap<>();
        percentiles.put("disc 0.5", dialect.generatePercentileDisc(0.5, false, "nums", "v").orElseThrow());
        percentiles.put("disc 0.5 desc", dialect.generatePercentileDisc(0.5, true, "nums", "v").orElseThrow());
        percentiles.put("disc 0.25 desc", dialect.generatePercentileDisc(0.25, true, null, "v").orElseThrow());
        percentiles.put("disc 0", dialect.generatePercentileDisc(0, false, "nums", "v").orElseThrow());
        percentiles.put("disc 1", dialect.generatePercentileDisc(1, false, "nums", "v").orElseThrow());
        percentiles.put("cont 0.5", dialect.generatePercentileCont(0.5, false, "nums", "v").orElseThrow());
        percentiles.put("cont 0.25", dialect.generatePercentileCont(0.25, false, null, "v").orElseThrow());
        percentiles.put("cont 0.25 desc", dialect.generatePercentileCont(0.25, true, "nums", "v").orElseThrow());
        percentiles.put("cont 1", dialect.generatePercentileCont(1, true, "nums", "v").orElseThrow());

        StringBuilder sql = new StringBuilder(SETUP);
        percentiles.forEach((label, p) -> sql.append(" SELECT '").append(label).append("', ").append(p)
                .append(" FROM nums WHERE g = 'a';"));
        sql.append(" SELECT 'per group ' || g, ").append(percentiles.get("cont 0.5"))
                .append(" FROM nums GROUP BY g ORDER BY g;");

        DuckDbCli.Result r = DuckDbCli.run(tag, sql.toString());
        assertThat(r.failed()).as("every percentile runs: " + r.err() + " in " + percentiles).isFalse();
        Map<String, Double> got = new LinkedHashMap<>();
        for (String line : r.out()) {
            int bar = line.indexOf('|');
            got.put(line.substring(0, bar), Double.parseDouble(line.substring(bar + 1)));
        }

        assertThat(got.get("disc 0.5")).as(percentiles.get("disc 0.5")).isEqualTo(20);
        assertThat(got.get("disc 0.5 desc")).as("descending: " + percentiles.get("disc 0.5 desc")).isEqualTo(30);
        assertThat(got.get("disc 0.25 desc")).as("descending, the column alone").isEqualTo(40);
        assertThat(got.get("disc 0")).as("the least").isEqualTo(10);
        assertThat(got.get("disc 1")).as("the greatest").isEqualTo(40);
        assertThat(got.get("cont 0.5")).as(percentiles.get("cont 0.5")).isEqualTo(25);
        assertThat(got.get("cont 0.25")).as("the column alone").isEqualTo(17.5);
        assertThat(got.get("cont 0.25 desc")).as("descending: " + percentiles.get("cont 0.25 desc"))
                .isEqualTo(32.5);
        assertThat(got.get("cont 1")).as("descending, the end: the least").isEqualTo(10);
        assertThat(got.get("per group a")).as("per group").isEqualTo(25);
        assertThat(got.get("per group b")).as("per group, one value").isEqualTo(5);
    }
}
