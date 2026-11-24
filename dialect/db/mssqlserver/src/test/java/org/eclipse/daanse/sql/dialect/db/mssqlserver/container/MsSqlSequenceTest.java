/*
 * Copyright (c) 2026 Contributors to the Eclipse Foundation.
 *
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.daanse.sql.dialect.db.mssqlserver.container;

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
import org.eclipse.daanse.sql.dialect.api.generator.DdlGenerator;
import org.eclipse.daanse.sql.dialect.api.generator.DdlGenerator.SequenceDefinition;
import org.eclipse.daanse.sql.dialect.db.mssqlserver.MicrosoftSqlServerDialect;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.containers.MSSQLServerContainer;

/**
 * SQL Server has sequences from 2012 on, so in every version Docker serves, 2017 to 2025. The dialect is built from a
 * live connection: the sequence it creates gives its values from {@code NEXT VALUE FOR}, with its start, increment,
 * bounds, cycle and cache, also as the default of a column; created again it is refused, with {@code ifNotExists} it
 * is left as it is; renamed it goes on counting, unless a column's default uses it; dropped it is gone, a second
 * drop passing with IF EXISTS only. A sequence of no options starts, as SQL Server's do, at the least BIGINT, not at
 * 1.
 */
@EnabledIfSystemProperty(named = "integration.docker", matches = "true")
class MsSqlSequenceTest {

    /** 10, 15, 20, then round to 10 again. */
    private static final SequenceDefinition CYCLING = new SequenceDefinition("dbo", "cycling", 10L, 5L, 10L, 20L,
            true, 3L);
    /** 1, 2, then no more. */
    private static final SequenceDefinition BOUNDED = new SequenceDefinition("dbo", "bounded", 1L, 1L, 1L, 2L, false,
            null);

    @ParameterizedTest(name = "mssql/server:{0}")
    @ValueSource(strings = { "2017-latest", "2019-latest", "2022-latest", "2025-latest" })
    void sequences(String tag) throws Exception {
        try (MSSQLServerContainer<?> ms = new MSSQLServerContainer<>("mcr.microsoft.com/mssql/server:" + tag)
                .acceptLicense()) {
            ms.start();
            try (Connection c = DriverManager.getConnection(ms.getJdbcUrl(), ms.getUsername(), ms.getPassword());
                    Statement st = c.createStatement()) {
                MicrosoftSqlServerDialect dialect = new MicrosoftSqlServerDialect(DialectInitData.fromConnection(c));
                DdlGenerator ddl = dialect.ddlGenerator();
                assertThat(ddl.supportsSequences()).as("supportsSequences").isTrue();

                // options: start, increment, bounds, cycle, cache
                String cycling = ddl.createSequence(CYCLING, false).orElseThrow();
                assertThat(cycling).isEqualTo("CREATE SEQUENCE \"dbo\".\"cycling\" START WITH 10 INCREMENT BY 5"
                        + " MINVALUE 10 MAXVALUE 20 CYCLE CACHE 3");
                st.execute(cycling);
                String next = ddl.nextValueFor("dbo", "cycling").orElseThrow();
                assertThat(values(st, next, 4)).as("start, increment, round at the bound")
                        .containsExactly("10", "15", "20", "10");

                st.execute(ddl.createSequence(BOUNDED, false).orElseThrow());
                String nextBounded = ddl.nextValueFor("dbo", "bounded").orElseThrow();
                assertThat(values(st, nextBounded, 2)).containsExactly("1", "2");
                assertThatThrownBy(() -> values(st, nextBounded, 1)).as("NO CYCLE past the bound")
                        .isInstanceOf(SQLException.class).hasMessageContaining("minimum or maximum value");

                // no options: SQL Server's own start, the least BIGINT
                st.execute(ddl.createSequence(SequenceDefinition.of("dbo", "plain"), false).orElseThrow());
                assertThat(values(st, ddl.nextValueFor("dbo", "plain").orElseThrow(), 1))
                        .as("no options: the least BIGINT").containsExactly(Long.toString(Long.MIN_VALUE));

                // the default of a column
                st.execute("CREATE TABLE items(id INT DEFAULT (" + next + "), name VARCHAR(10))");
                st.execute("INSERT INTO items(name) VALUES ('a'), ('b')");
                assertThat(rows(st, "SELECT CAST(id AS VARCHAR) + '|' + name FROM items ORDER BY name"))
                        .as("the default of a column").containsExactly("15|a", "20|b");

                // created again: refused; with ifNotExists left as it is
                assertThatThrownBy(() -> st.execute(cycling)).as("created again")
                        .isInstanceOf(SQLException.class).hasMessageContaining("already an object named");
                String ifNotExists = ddl.createSequence(CYCLING, true).orElseThrow();
                st.execute(ifNotExists);
                assertThat(values(st, next, 1)).as("with ifNotExists left as it is: " + ifNotExists)
                        .containsExactly("10");

                // renamed, it goes on counting; one a column's default uses is not renamed
                assertThatThrownBy(() -> st.execute(ddl.renameSequence("dbo", "cycling", "renamed").orElseThrow()))
                        .as("a sequence a column's default uses").isInstanceOf(SQLException.class)
                        .hasMessageContaining("enforced dependencies");
                st.execute("DROP SEQUENCE IF EXISTS dbo.bounded");
                st.execute(ddl.createSequence(BOUNDED, false).orElseThrow());
                assertThat(values(st, nextBounded, 1)).containsExactly("1");
                st.execute(ddl.renameSequence("dbo", "bounded", "renamed").orElseThrow());
                assertThat(values(st, ddl.nextValueFor("dbo", "renamed").orElseThrow(), 1)).as("renamed")
                        .containsExactly("2");
                assertThatThrownBy(() -> values(st, nextBounded, 1)).as("the old name gone")
                        .isInstanceOf(SQLException.class);

                // dropped
                String drop = ddl.dropSequence("dbo", "renamed", true).orElseThrow();
                assertThat(drop).isEqualTo("DROP SEQUENCE IF EXISTS \"dbo\".\"renamed\"");
                st.execute(drop);
                assertThat(rows(st, "SELECT name FROM sys.sequences WHERE name = 'renamed'")).as("dropped").isEmpty();
                st.execute(drop);
                assertThatThrownBy(() -> st.execute(ddl.dropSequence("dbo", "renamed", false).orElseThrow()))
                        .as("a missing one without IF EXISTS").isInstanceOf(SQLException.class)
                        .hasMessageContaining("does not exist");
            }
        }
    }

    /** The next n values of the sequence, one statement each. */
    private static List<String> values(Statement st, String next, int n) throws SQLException {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.addAll(rows(st, "SELECT " + next));
        }
        return out;
    }

    private static List<String> rows(Statement st, String sql) throws SQLException {
        List<String> out = new ArrayList<>();
        try (ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                out.add(rs.getString(1));
            }
        }
        return out;
    }
}
