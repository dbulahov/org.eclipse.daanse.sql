/*
 * Copyright (c) 2026 Contributors to the Eclipse Foundation.
 *
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.daanse.sql.dialect.db.postgresql.container;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import org.eclipse.daanse.sql.model.sql.BitOperation;
import org.eclipse.daanse.sql.dialect.api.DialectInitData;
import org.eclipse.daanse.sql.dialect.db.postgresql.PostgreSqlDialect;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * PostgreSQL has {@code bit_and} and {@code bit_or} of old, {@code bit_xor} only since 14. The dialect is built from a
 * live connection: each bit aggregation it offers must compute the right value on the server, and on 13 it must not
 * offer XOR and NXOR, which the server would refuse.
 */
@EnabledIfSystemProperty(named = "integration.docker", matches = "true")
class PostgreSqlBitAggregationTest {

    /** 14 = 1110, 11 = 1011, 7 = 0111; the NULL is left out by every aggregate. */
    private static final String SETUP = """
            CREATE TABLE flags(v INT);
            INSERT INTO flags VALUES (14), (11), (NULL), (7);
            """;

    /** AND = 0010, OR = 1111, XOR = 0010; NAND, NOR and NXOR are their bitwise complements. */
    private static final Map<BitOperation, Integer> EXPECTED = Map.of(BitOperation.AND, 2, BitOperation.OR, 15,
            BitOperation.XOR, 2, BitOperation.NAND, ~2, BitOperation.NOR, ~15, BitOperation.NXOR, ~2);

    private static final Set<BitOperation> XOR = EnumSet.of(BitOperation.XOR, BitOperation.NXOR);

    @ParameterizedTest(name = "postgres:{0}, xor -> {1}")
    @CsvSource({ "13-alpine, false", "14-alpine, true", "16-alpine, true" })
    void bit_aggregation(String tag, boolean xorExpected) throws Exception {
        try (PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:" + tag)) {
            pg.start();
            try (Connection c = pg.createConnection(""); Statement st = c.createStatement()) {
                st.execute(SETUP);
                PostgreSqlDialect dialect = new PostgreSqlDialect(DialectInitData.fromConnection(c));

                for (BitOperation op : BitOperation.values()) {
                    boolean expected = xorExpected || !XOR.contains(op);
                    assertThat(dialect.supportsBitAggregation(op)).as(op + " supported").isEqualTo(expected);

                    var sql = dialect.generateBitAggregation(op, "v");
                    if (!expected) {
                        assertThat(sql).as(op + " is not generated").isEmpty();
                        continue;
                    }
                    assertThat(sql).as(op + " is generated").isPresent();
                    assertThat(value(st, "SELECT " + sql.get() + " FROM flags")).as(op + ": " + sql.get())
                            .isEqualTo(EXPECTED.get(op));
                }

                if (!xorExpected) {
                    assertThatThrownBy(() -> value(st, "SELECT bit_xor(v) FROM flags"))
                            .as("PostgreSQL 13 knows no bit_xor").isInstanceOf(SQLException.class)
                            .hasMessageContaining("does not exist");
                }
            }
        }
    }

    private static int value(Statement st, String sql) throws SQLException {
        try (ResultSet rs = st.executeQuery(sql)) {
            assertThat(rs.next()).isTrue();
            return rs.getInt(1);
        }
    }
}
