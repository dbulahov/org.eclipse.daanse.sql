/*
 * Copyright (c) 2026 Contributors to the Eclipse Foundation.
 *
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.daanse.sql.dialect.db.mariadb.container;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.eclipse.daanse.sql.dialect.api.DialectInitData;
import org.eclipse.daanse.sql.dialect.db.mariadb.MariaDBDialect;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.testcontainers.containers.MariaDBContainer;

/**
 * MariaDB has {@code PERCENTILE_DISC} and {@code PERCENTILE_CONT … WITHIN GROUP} as window functions, with an
 * {@code OVER} clause, from 10.3.3. The dialect writes a percentile as an aggregate of a group, without one, and is
 * built from a live connection: where it offers a percentile, the server must compute it as such an aggregate, per
 * group too; where the server takes it as a window function only, as every version here does, the dialect must offer
 * none and write none.
 */
@EnabledIfSystemProperty(named = "integration.docker", matches = "true")
class MariaDBPercentileTest {

    /** Group a: 10, 20, 30, 40 and a NULL that every aggregate leaves out; group b: 5. */
    private static final List<String> SETUP = List.of("CREATE TABLE nums(grp VARCHAR(1), v INT)",
            "INSERT INTO nums VALUES ('a', 30), ('a', 10), ('a', NULL), ('a', 40), ('a', 20), ('b', 5)");

    @ParameterizedTest(name = "mariadb:{0} -> percentiles {1}")
    @CsvSource({ "10.4, false", "10.6, false", "10.11, false", "11.4, false" })
    void percentile(String tag, boolean expected) throws Exception {
        try (MariaDBContainer<?> db = new MariaDBContainer<>("mariadb:" + tag).withDatabaseName("rt")
                .withUsername("rt").withPassword("rt")) {
            db.start();
            try (Connection c = db.createConnection(""); Statement st = c.createStatement()) {
                for (String sql : SETUP) {
                    st.execute(sql);
                }
                MariaDBDialect dialect = new MariaDBDialect(DialectInitData.fromConnection(c));

                assertThat(rows(st, "SELECT DISTINCT PERCENTILE_CONT(0.5) WITHIN GROUP (ORDER BY v)"
                        + " OVER (PARTITION BY grp) FROM nums WHERE grp = 'a'"))
                        .as("MariaDB has the percentile as a window function").containsExactly("25");

                assertThat(dialect.supportsPercentileDisc()).as("supportsPercentileDisc").isEqualTo(expected);
                assertThat(dialect.supportsPercentileCont()).as("supportsPercentileCont").isEqualTo(expected);

                if (!expected) {
                    assertThat(dialect.generatePercentileDisc(0.5, false, "nums", "v")).isEmpty();
                    assertThat(dialect.generatePercentileCont(0.5, false, "nums", "v")).isEmpty();
                    assertThatThrownBy(() -> rows(st,
                            "SELECT PERCENTILE_CONT(0.5) WITHIN GROUP (ORDER BY v) FROM nums WHERE grp = 'a'"))
                            .as("not as an aggregate of a group").isInstanceOf(SQLException.class);
                    assertThatThrownBy(() -> rows(st,
                            "SELECT PERCENTILE_DISC(0.5) WITHIN GROUP (ORDER BY v) FROM nums WHERE grp = 'a'"))
                            .as("not as an aggregate of a group").isInstanceOf(SQLException.class);
                    return;
                }

                String disc = dialect.generatePercentileDisc(0.5, false, "nums", "v").orElseThrow();
                String cont = dialect.generatePercentileCont(0.5, false, "nums", "v").orElseThrow();
                assertThat(rows(st, "SELECT " + disc + " FROM nums WHERE grp = 'a'")).containsExactly("20");
                assertThat(rows(st, "SELECT " + cont + " FROM nums WHERE grp = 'a'")).containsExactly("25");
                assertThat(rows(st, "SELECT CONCAT(grp, '=', " + cont + ") FROM nums GROUP BY grp ORDER BY grp"))
                        .as("per group").containsExactly("a=25", "b=5");
            }
        }
    }

    /** The first column of each row; a number without the zeros of its fraction. */
    private static List<String> rows(Statement st, String sql) throws SQLException {
        List<String> out = new ArrayList<>();
        try (ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                out.add(Optional.ofNullable(rs.getString(1))
                        .map(s -> s.contains(".") ? s.replaceAll("0+$", "").replaceAll("\\.$", "") : s).orElse(null));
            }
        }
        return out;
    }
}
