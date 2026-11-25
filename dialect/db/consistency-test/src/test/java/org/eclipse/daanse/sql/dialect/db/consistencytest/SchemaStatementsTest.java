/*
 * Copyright (c) 2026 Contributors to the Eclipse Foundation.
 *
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.daanse.sql.dialect.db.consistencytest;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;

import org.eclipse.daanse.sql.dialect.api.Dialect;
import org.eclipse.daanse.sql.dialect.api.generator.DdlGenerator;
import org.eclipse.daanse.sql.dialect.db.clickhouse.ClickHouseDialect;
import org.eclipse.daanse.sql.dialect.db.common.AnsiDialect;
import org.eclipse.daanse.sql.dialect.db.derby.DerbyDialect;
import org.eclipse.daanse.sql.dialect.db.duckdb.DuckDbDialect;
import org.eclipse.daanse.sql.dialect.db.h2.H2Dialect;
import org.eclipse.daanse.sql.dialect.db.hive.HiveDialect;
import org.eclipse.daanse.sql.dialect.db.impala.ImpalaDialect;
import org.eclipse.daanse.sql.dialect.db.mariadb.MariaDBDialect;
import org.eclipse.daanse.sql.dialect.db.mssqlserver.MicrosoftSqlServerDialect;
import org.eclipse.daanse.sql.dialect.db.mysql.MySqlDialect;
import org.eclipse.daanse.sql.dialect.db.oracle.OracleDialect;
import org.eclipse.daanse.sql.dialect.db.postgresql.PostgreSqlDialect;
import org.eclipse.daanse.sql.dialect.db.sqlite.SqliteDialect;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Which dialect has schemas to create and drop: {@link DdlGenerator#supportsSchemas()}, and a statement from
 * {@code createSchema} and {@code dropSchema} where it says yes, none where it says no. SQLite alone has no schemas
 * inside a database. What the statements of the others say is left to the tests of each dialect.
 */
class SchemaStatementsTest {

    static Stream<Arguments> dialects() {
        return Stream.of(of("postgresql", new PostgreSqlDialect(), true), of("h2", new H2Dialect(), true),
                of("duckdb", new DuckDbDialect(), true), of("ansi", new AnsiDialect(), true),
                of("oracle", new OracleDialect(), true), of("mssql", new MicrosoftSqlServerDialect(), true),
                of("mysql", new MySqlDialect(), true), of("mariadb", new MariaDBDialect(), true),
                of("clickhouse", new ClickHouseDialect(), true), of("hive", new HiveDialect(), true),
                of("impala", new ImpalaDialect(), true), of("derby", new DerbyDialect(), true),
                of("sqlite", new SqliteDialect(), false));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dialects")
    void createsAndDropsASchema(Dialect dialect, boolean schemas) {
        DdlGenerator ddl = dialect.ddlGenerator();
        assertThat(ddl.supportsSchemas()).as("supportsSchemas").isEqualTo(schemas);
        if (schemas) {
            assertThat(ddl.createSchema("s", true)).as("createSchema").isNotBlank();
            assertThat(ddl.dropSchema("s", true, true)).as("dropSchema").isNotBlank();
        } else {
            assertThat(ddl.createSchema("s", true)).as("no createSchema").isNull();
            assertThat(ddl.dropSchema("s", true, true)).as("no dropSchema").isNull();
        }
    }

    private static Arguments of(String name, Dialect dialect, boolean schemas) {
        return Arguments.of(Named.of(name, dialect), schemas);
    }
}
