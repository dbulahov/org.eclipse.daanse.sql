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
import static org.assertj.core.api.Assertions.within;

import java.util.LinkedHashMap;
import java.util.Map;

import org.eclipse.daanse.sql.dialect.db.sqlite.SqliteDialect;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * SQLite has no {@code PERCENTILE_CONT … WITHIN GROUP}, and its own {@code percentile_cont} and
 * {@code percentile_disc} are in some builds only, as {@code ceil} is. The dialect computes both on the sorted values
 * of {@code json_group_array}, from 3.44.0, where an aggregate takes an ORDER BY. SQLite is embedded: the CLI of each
 * version runs the statements in Docker, and the dialect is built for the version the CLI reports. From 3.44 on its
 * percentiles must give what the standard says, on builds with and without the functions of their own: the discrete
 * one a value of the group, the continuous one interpolated, ascending and descending, the NULLs left out, p at its
 * ends, per group, and NULL for a group of no value. Before, it offers none.
 */
@EnabledIfSystemProperty(named = "integration.docker", matches = "true")
class SqlitePercentileTest {

    /** Group a: 10, 20, 30, 40 and a NULL that every aggregate leaves out; group b: 5; group c: only a NULL. */
    private static final String SETUP = "CREATE TABLE nums(g TEXT, v INTEGER); INSERT INTO nums VALUES ('a', 30),"
            + " ('a', 10), ('a', NULL), ('a', 40), ('a', 20), ('b', 5), ('c', NULL);\n";

    /**
     * 3.47.0 and 3.50.1 are builds with percentile_cont and ceil, 3.46.1 and 3.51.0 builds without; keinos/sqlite3's
     * 3.49.0 holds 3.44.4 and is not run.
     */
    @ParameterizedTest(name = "sqlite {0} -> percentiles {1}")
    @CsvSource({ "3.22.0, false", "3.43.1, false", "3.44.0, true", "3.46.1, true", "3.47.0, true", "3.50.1, true",
            "3.51.0, true" })
    void percentile(String version, boolean expected) {
        SqliteDialect d = SqliteCli.dialect(version);
        assertThat(d.supportsPercentileDisc()).as("supportsPercentileDisc").isEqualTo(expected);
        assertThat(d.supportsPercentileCont()).as("supportsPercentileCont").isEqualTo(expected);
        if (!expected) {
            assertThat(d.generatePercentileDisc(0.5, false, "nums", "v")).as("none before 3.44").isEmpty();
            assertThat(d.generatePercentileCont(0.5, false, "nums", "v")).as("none before 3.44").isEmpty();
            return;
        }

        Map<String, String> p = new LinkedHashMap<>();
        p.put("disc 0.5", d.generatePercentileDisc(0.5, false, "nums", "v").orElseThrow());
        p.put("disc 0.25", d.generatePercentileDisc(0.25, false, null, "v").orElseThrow());
        p.put("disc 0.7", d.generatePercentileDisc(0.7, false, null, "v").orElseThrow());
        p.put("disc 0", d.generatePercentileDisc(0, false, null, "v").orElseThrow());
        p.put("disc 1", d.generatePercentileDisc(1, false, null, "v").orElseThrow());
        p.put("disc 0.5 desc", d.generatePercentileDisc(0.5, true, null, "v").orElseThrow());
        p.put("disc 0.25 desc", d.generatePercentileDisc(0.25, true, null, "v").orElseThrow());
        p.put("cont 0.5", d.generatePercentileCont(0.5, false, "nums", "v").orElseThrow());
        p.put("cont 0.25", d.generatePercentileCont(0.25, false, null, "v").orElseThrow());
        p.put("cont 0.7", d.generatePercentileCont(0.7, false, null, "v").orElseThrow());
        p.put("cont 0", d.generatePercentileCont(0, false, null, "v").orElseThrow());
        p.put("cont 1", d.generatePercentileCont(1, false, null, "v").orElseThrow());
        p.put("cont 0.25 desc", d.generatePercentileCont(0.25, true, null, "v").orElseThrow());
        p.put("cont 1 desc", d.generatePercentileCont(1, true, null, "v").orElseThrow());

        StringBuilder sql = new StringBuilder(SETUP);
        p.forEach((label, agg) -> sql.append("SELECT '").append(label).append("|' || ifnull(").append(agg)
                .append(", 'null') FROM nums WHERE g = 'a';\n"));
        String cont = d.generatePercentileCont(0.5, false, null, "v").orElseThrow();
        String disc = d.generatePercentileDisc(0.5, false, null, "v").orElseThrow();
        sql.append("SELECT 'cont per group|' || group_concat(g || '=' || ifnull(x, 'null'), ' ') FROM (SELECT g, ")
                .append(cont).append(" AS x FROM nums GROUP BY g ORDER BY g);\n");
        sql.append("SELECT 'disc per group|' || group_concat(g || '=' || ifnull(x, 'null'), ' ') FROM (SELECT g, ")
                .append(disc).append(" AS x FROM nums GROUP BY g ORDER BY g);\n");

        SqliteCli.Result r = SqliteCli.run(version, sql.toString());
        assertThat(r.failed()).as("every percentile runs: " + r.err() + " in " + p).isFalse();
        Map<String, String> got = new LinkedHashMap<>();
        for (String line : r.out()) {
            int bar = line.indexOf('|');
            if (bar > 0) {
                got.put(line.substring(0, bar), line.substring(bar + 1));
            }
        }

        assertThat(num(got, "disc 0.5")).as(p.get("disc 0.5")).isEqualTo(20);
        assertThat(num(got, "disc 0.25")).as("cume_dist of 10 is 0.25").isEqualTo(10);
        assertThat(num(got, "disc 0.7")).isEqualTo(30);
        assertThat(num(got, "disc 0")).as("the least").isEqualTo(10);
        assertThat(num(got, "disc 1")).as("the greatest").isEqualTo(40);
        assertThat(num(got, "disc 0.5 desc")).as("descending").isEqualTo(30);
        assertThat(num(got, "disc 0.25 desc")).isEqualTo(40);
        assertThat(num(got, "cont 0.5")).as(p.get("cont 0.5")).isEqualTo(25);
        assertThat(num(got, "cont 0.25")).isEqualTo(17.5);
        assertThat(num(got, "cont 0.7")).isCloseTo(31, within(1e-9));
        assertThat(num(got, "cont 0")).as("the least").isEqualTo(10);
        assertThat(num(got, "cont 1")).as("the greatest").isEqualTo(40);
        assertThat(num(got, "cont 0.25 desc")).as("descending").isEqualTo(32.5);
        assertThat(num(got, "cont 1 desc")).as("descending, the end: the least").isEqualTo(10);
        assertThat(got.get("cont per group")).as("one value, no value").matches("a=25(\\.0)? b=5(\\.0)? c=null");
        assertThat(got.get("disc per group")).as("one value, no value").isEqualTo("a=20 b=5 c=null");
    }

    private static double num(Map<String, String> got, String label) {
        assertThat(got.get(label)).as(label).isNotNull();
        return Double.parseDouble(got.get(label));
    }
}
