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
import org.eclipse.daanse.sql.model.sql.BitOperation;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * DuckDB has {@code bit_and}, {@code bit_or} and {@code bit_xor}, in every version from 1.0 on, and the dialect writes
 * NAND, NOR and NXOR as their complement {@code ~(…)}. The CLI of each version runs what the dialect writes, in Docker,
 * and the dialect is built for the version the CLI reports: every operation is offered and computes the right value,
 * of an INTEGER column and of a BIGINT column past 32 bits, leaving out the NULL, per group, and NULL for a group of
 * NULLs alone.
 */
@EnabledIfSystemProperty(named = "integration.docker", matches = "true")
class DuckDbBitAggregationTest {

    /**
     * Group a: 14 = 1110, 11 = 1011, 7 = 0111 and a NULL; group b: 5 alone; group c: a NULL alone. The BIGINT column
     * holds the same values shifted by 40 bits.
     */
    private static final String SETUP = "CREATE TABLE flags(g VARCHAR, v INTEGER, w BIGINT);"
            + " INSERT INTO flags VALUES ('a', 14, 14::BIGINT << 40), ('a', 11, 11::BIGINT << 40), ('a', NULL, NULL),"
            + " ('a', 7, 7::BIGINT << 40), ('b', 5, 5::BIGINT << 40), ('c', NULL, NULL);";

    /** AND = 0010, OR = 1111, XOR = 0010; NAND, NOR and NXOR are their bitwise complements. */
    private static final Map<BitOperation, Long> EXPECTED = Map.of(BitOperation.AND, 2L, BitOperation.OR, 15L,
            BitOperation.XOR, 2L, BitOperation.NAND, ~2L, BitOperation.NOR, ~15L, BitOperation.NXOR, ~2L);

    @ParameterizedTest(name = "duckdb:{0}")
    @ValueSource(strings = { "1.0.0", "1.1.3", "1.2.2", "1.3.2", "1.4.4", "1.5.6", "2.0.1" })
    void bitAggregation(String tag) {
        DuckDbDialect dialect = DuckDbCli.dialect(tag);

        StringBuilder sql = new StringBuilder(SETUP);
        Map<String, String> generated = new LinkedHashMap<>();
        for (BitOperation op : BitOperation.values()) {
            assertThat(dialect.supportsBitAggregation(op)).as(op + " supported").isTrue();
            String v = dialect.generateBitAggregation(op, "v").orElseThrow();
            String w = dialect.generateBitAggregation(op, "w").orElseThrow();
            generated.put(op.name(), v);
            sql.append(" SELECT '").append(op).append("', ").append(v).append(", ").append(w)
                    .append(" FROM flags WHERE g = 'a';");
            sql.append(" SELECT '").append(op).append(" per group ' || g, COALESCE(CAST(").append(v)
                    .append(" AS VARCHAR), 'null') FROM flags GROUP BY g ORDER BY g;");
        }

        DuckDbCli.Result r = DuckDbCli.run(tag, sql.toString());
        assertThat(r.failed()).as("every operation runs: " + r.err() + " in " + generated).isFalse();
        Map<String, String> got = new LinkedHashMap<>();
        for (String line : r.out()) {
            int bar = line.indexOf('|');
            got.put(line.substring(0, bar), line.substring(bar + 1));
        }

        for (BitOperation op : BitOperation.values()) {
            long expected = EXPECTED.get(op);
            long b = op == BitOperation.AND || op == BitOperation.OR || op == BitOperation.XOR ? 5L : ~5L;
            assertThat(got.get(op.name())).as(op + ": " + generated.get(op.name()) + ", INTEGER and BIGINT")
                    .isEqualTo(expected + "|" + bigint(op));
            assertThat(got.get(op + " per group a")).as(op + " per group").isEqualTo(Long.toString(expected));
            assertThat(got.get(op + " per group b")).as(op + " of one value").isEqualTo(Long.toString(b));
            assertThat(got.get(op + " per group c")).as(op + " of NULLs alone").isEqualTo("null");
        }
    }

    /** The value of the operation on the BIGINT column: the values shifted by 40 bits. */
    private static long bigint(BitOperation op) {
        long and = 2L << 40, or = 15L << 40, xor = 2L << 40;
        return switch (op) {
        case AND -> and;
        case OR -> or;
        case XOR -> xor;
        case NAND -> ~and;
        case NOR -> ~or;
        case NXOR -> ~xor;
        };
    }
}
