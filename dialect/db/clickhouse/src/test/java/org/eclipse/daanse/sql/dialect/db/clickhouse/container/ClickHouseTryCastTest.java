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

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import org.eclipse.daanse.sql.dialect.api.DialectInitData;
import org.eclipse.daanse.sql.dialect.api.generator.CastGenerator;
import org.eclipse.daanse.sql.dialect.db.clickhouse.ClickHouseDialect;
import org.eclipse.daanse.sql.model.type.Datatype;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.clickhouse.ClickHouseContainer;

/**
 * ClickHouse casts with {@code accurateCastOrNull(x, 'T')} to NULL what does not fit, on every version: what does not
 * parse, what overflows, what would lose a part of itself; a plain {@code CAST} wraps an overflow round. The dialect is
 * built from a live connection: its casts, in ClickHouse's own type names, run for every type it maps, and its try
 * cast gives the value where it fits and NULL where it does not, a boolean too, which ClickHouse's own
 * {@code accurateCastOrNull} gets wrong before 24.3. A date of a month out of range ClickHouse does not take for one
 * that does not fit.
 */
@EnabledIfSystemProperty(named = "integration.docker", matches = "true")
class ClickHouseTryCastTest {

    /** Each type the dialect maps, with a value of it. */
    private static final List<Object[]> VALUES = List.of(new Object[] { Datatype.VARCHAR, "'abc'", "abc" },
            new Object[] { Datatype.BINARY, "'abc'", "abc" }, new Object[] { Datatype.INTEGER, "'42'", "42" },
            new Object[] { Datatype.BIGINT, "'9000000000'", "9000000000" },
            new Object[] { Datatype.SMALLINT, "'-7'", "-7" }, new Object[] { Datatype.DOUBLE, "'2.5'", "2.5" },
            new Object[] { Datatype.FLOAT, "'2.5'", "2.5" }, new Object[] { Datatype.REAL, "'2.5'", "2.5" },
            new Object[] { Datatype.DECIMAL, "'12.345'", "12.345" },
            new Object[] { Datatype.NUMERIC, "'12.345'", "12.345" },
            new Object[] { Datatype.DATE, "'2020-02-03'", "2020-02-03" },
            new Object[] { Datatype.TIMESTAMP, "'2020-02-03 04:05:06.789'", "2020-02-03 04:05:06.789" },
            new Object[] { Datatype.UUID, "'6ba7b810-9dad-11d1-80b4-00c04fd430c8'",
                    "6ba7b810-9dad-11d1-80b4-00c04fd430c8" });

    @ParameterizedTest(name = "clickhouse:{0}")
    @ValueSource(strings = { "21.3", "21.8", "21.12", "23.8", "24.3", "24.8", "25.8", "26.6" })
    void try_cast(String tag) throws Exception {
        try (ClickHouseContainer ch = ClickHouseContainers.container(tag)) {
            ch.start();
            try (Connection c = DriverManager.getConnection(ClickHouseContainers.jdbcUrl(ch, tag), ch.getUsername(),
                    ch.getPassword()); Statement st = c.createStatement()) {
                CastGenerator g = new ClickHouseDialect(DialectInitData.fromConnection(c)).castGenerator();
                assertThat(g.supportsTryCast()).as("supportsTryCast").isTrue();

                for (Object[] v : VALUES) {
                    Datatype type = (Datatype) v[0];
                    String literal = (String) v[1];
                    String expected = (String) v[2];
                    // a Decimal as its number: 21.x writes it with its scale's zeros
                    String shown = type == Datatype.DECIMAL || type == Datatype.NUMERIC ? "toString(toFloat64(%s))"
                            : "toString(%s)";
                    assertThat(one(st, shown.formatted(g.cast(literal, type)))).as("CAST AS " + type)
                            .isEqualTo(expected);
                    assertThat(one(st, shown.formatted(g.tryCast(literal, type)))).as("try cast " + type)
                            .isEqualTo(expected);
                }

                assertThat(one(st, g.tryCast("'abc'", Datatype.INTEGER))).as("no number").isNull();
                assertThat(one(st, g.tryCast("'3000000000'", Datatype.INTEGER))).as("too large for Int32").isNull();
                assertThat(one(st, g.tryCast("300", Datatype.SMALLINT) + " IS NULL")).as("300 fits Int16")
                        .isEqualTo("0");
                assertThat(one(st, g.tryCast("100000", Datatype.SMALLINT))).as("too large for Int16").isNull();
                assertThat(one(st, "toString(CAST(100000 AS Int16))")).as("a plain CAST wraps it round")
                        .isEqualTo("-31072");
                assertThat(one(st, g.tryCast("3.7", Datatype.INTEGER))).as("a part of it lost").isNull();
                assertThat(one(st, g.tryCast("'x'", Datatype.DECIMAL))).as("no decimal").isNull();
                assertThat(one(st, g.tryCast("'abc'", Datatype.DATE))).as("no date").isNull();
                assertThat(one(st, g.tryCast("'abc'", Datatype.TIMESTAMP))).as("no timestamp").isNull();
                assertThat(one(st, g.tryCast("'zz'", Datatype.UUID))).as("no UUID").isNull();
                assertThat(one(st, g.tryCast("NULL", Datatype.INTEGER))).as("NULL").isNull();
                assertThat(one(st, "toString(" + g.tryCast("'2020-13-45'", Datatype.DATE) + ")"))
                        .as("a month out of range is no NULL to ClickHouse").isNotNull();

                for (String yes : List.of("'true'", "'1'", "1")) {
                    assertThat(one(st, "toString(toUInt8(" + g.tryCast(yes, Datatype.BOOLEAN) + "))"))
                            .as("true: " + yes).isEqualTo("1");
                }
                for (String no : List.of("'false'", "'0'", "0")) {
                    assertThat(one(st, "toString(toUInt8(" + g.tryCast(no, Datatype.BOOLEAN) + "))"))
                            .as("false: " + no).isEqualTo("0");
                }
                assertThat(one(st, g.tryCast("'yes?'", Datatype.BOOLEAN))).as("no boolean, and no error")
                        .isNull();

                st.execute("CREATE TABLE t(s Nullable(String)) ENGINE = MergeTree() ORDER BY tuple()");
                st.execute("INSERT INTO t VALUES ('1'), ('x'), (NULL), ('40')");
                assertThat(one(st, "toString(sum(" + g.tryCast("s", Datatype.INTEGER) + ")) FROM t"))
                        .as("over a column, what does not fit left out").isEqualTo("41");
            }
        }
    }

    /** The one value of {@code SELECT expr}, NULL as null. */
    private static String one(Statement st, String expr) throws SQLException {
        String sql = "SELECT " + expr;
        try (ResultSet rs = st.executeQuery(sql)) {
            assertThat(rs.next()).isTrue();
            return rs.getString(1);
        } catch (SQLException e) {
            throw new SQLException(e.getMessage() + " in " + sql, e);
        }
    }
}
