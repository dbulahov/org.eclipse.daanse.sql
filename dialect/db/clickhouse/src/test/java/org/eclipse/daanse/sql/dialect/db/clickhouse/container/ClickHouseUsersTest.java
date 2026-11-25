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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.eclipse.daanse.sql.dialect.api.DialectInitData;
import org.eclipse.daanse.sql.dialect.db.clickhouse.ClickHouseDialect;
import org.eclipse.daanse.sql.model.schema.TableReference;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.clickhouse.ClickHouseContainer;

/**
 * ClickHouse has users from 20.4 (access control by SQL), and lets a user without a password in from any host: the
 * dialect creates a user with no host to come from, {@code CREATE USER [IF NOT EXISTS] "x" HOST NONE}, and drops it
 * with {@code DROP USER [IF EXISTS] "x"}. The dialect is built from a live connection, and the user it creates must
 * be there, without a password and a host, not log in, take a privilege, log in once an administrator gives it a
 * password and a host, and be dropped; IF [NOT] EXISTS spares an error. The images of
 * {@code clickhouse/clickhouse-server} begin with 21.3, so the boundary itself is not run here.
 */
@EnabledIfSystemProperty(named = "integration.docker", matches = "true")
class ClickHouseUsersTest {

    private static final TableReference ITEMS = new TableReference(Optional.empty(), "items",
            TableReference.TYPE_TABLE);

    /** Authentication and the number of hosts of the user; auth_type is an array from 25.x, a value before. */
    private static final String USER = """
            SELECT concat(name, '|', replaceRegexpAll(toString(auth_type), '[^a-z_]', ''), '|',
                toString(length(host_ip) + length(host_names) + length(host_names_regexp) + length(host_names_like)))
            FROM system.users WHERE name = 'rt_user'""";

    @ParameterizedTest(name = "clickhouse:{0}")
    @ValueSource(strings = { "21.3", "21.8", "23.8", "24.8", "25.8", "26.6" })
    void users(String tag) throws Exception {
        // the container's user may manage users only with access management turned on
        try (ClickHouseContainer ch = ClickHouseContainers.container(tag)) {
            ch.withEnv("CLICKHOUSE_DEFAULT_ACCESS_MANAGEMENT", "1");
            ch.start();
            String url = ClickHouseContainers.jdbcUrl(ch, tag);
            try (Connection c = DriverManager.getConnection(url, ch.getUsername(), ch.getPassword());
                    Statement st = c.createStatement()) {
                st.execute("CREATE TABLE items(id Int32) ENGINE = MergeTree() ORDER BY id");
                st.execute("INSERT INTO items VALUES (1)");
                ClickHouseDialect dialect = new ClickHouseDialect(DialectInitData.fromConnection(c));
                assertThat(dialect.supportsUsers()).as("supportsUsers").isTrue();

                execute(st, dialect.createUser("rt_user", false).orElseThrow());
                assertThat(rows(st, USER)).as("the user, without a password and without a host")
                        .containsExactly("rt_user|no_password|0");
                assertThatThrownBy(() -> select(url, "rt_user", ""))
                        .as("a user without a host does not log in").isInstanceOf(SQLException.class);

                assertThatThrownBy(() -> st.execute(dialect.createUser("rt_user", false).orElseThrow()))
                        .as("a second one of the name").isInstanceOf(SQLException.class);
                execute(st, dialect.createUser("rt_user", true).orElseThrow());

                execute(st, dialect.grantTablePrivilege("SELECT", ITEMS, "rt_user", false));
                assertThat(String.join(" ", rows(st, "SHOW GRANTS FOR rt_user"))).as("a privilege of the user")
                        .contains("GRANT SELECT ON default.items TO rt_user");

                st.execute("ALTER USER rt_user IDENTIFIED WITH plaintext_password BY 'pw' HOST ANY");
                assertThat(select(url, "rt_user", "pw")).as("logs in once given a password and a host, and reads")
                        .isEqualTo("1");

                execute(st, dialect.dropUser("rt_user", false).orElseThrow());
                assertThat(rows(st, USER)).as("dropped").isEmpty();
                assertThatThrownBy(() -> st.execute(dialect.dropUser("rt_user", false).orElseThrow()))
                        .as("a missing one").isInstanceOf(SQLException.class);
                execute(st, dialect.dropUser("rt_user", true).orElseThrow());
            }
        }
    }

    /** The table as the user reads it, which logs it in: the driver may not ask the server before. */
    private static String select(String url, String user, String password) throws SQLException {
        try (Connection c = DriverManager.getConnection(url, user, password); Statement st = c.createStatement();
                ResultSet rs = st.executeQuery("SELECT toString(id) FROM items")) {
            assertThat(rs.next()).isTrue();
            return rs.getString(1);
        }
    }

    private static void execute(Statement st, String sql) throws SQLException {
        try {
            st.execute(sql);
        } catch (SQLException e) {
            throw new SQLException(e.getMessage() + " in " + sql, e);
        }
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
