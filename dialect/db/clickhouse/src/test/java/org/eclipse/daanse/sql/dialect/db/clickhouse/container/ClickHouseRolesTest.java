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
 * ClickHouse has roles from 20.4 (access control by SQL): {@code CREATE ROLE [IF NOT EXISTS]},
 * {@code DROP ROLE [IF EXISTS]}, a role granted to a user, with the admin option too, and revoked. The dialect is
 * built from a live connection, and the role it creates must be there, hold a privilege, give it to the user it is
 * granted to, which then reads what it could not before, and be dropped, the grant going with it; IF [NOT] EXISTS
 * spares an error. The images of {@code clickhouse/clickhouse-server} begin with 21.3, so the boundary itself is not
 * run here.
 */
@EnabledIfSystemProperty(named = "integration.docker", matches = "true")
class ClickHouseRolesTest {

    private static final TableReference ITEMS = new TableReference(Optional.empty(), "items",
            TableReference.TYPE_TABLE);

    @ParameterizedTest(name = "clickhouse:{0}")
    @ValueSource(strings = { "21.3", "21.8", "23.8", "24.8", "25.8", "26.6" })
    void roles(String tag) throws Exception {
        // the container's user may manage roles and users only with access management turned on
        try (ClickHouseContainer ch = ClickHouseContainers.container(tag)) {
            ch.withEnv("CLICKHOUSE_DEFAULT_ACCESS_MANAGEMENT", "1");
            ch.start();
            String url = ClickHouseContainers.jdbcUrl(ch, tag);
            try (Connection c = DriverManager.getConnection(url, ch.getUsername(), ch.getPassword());
                    Statement st = c.createStatement()) {
                st.execute("CREATE TABLE items(id Int32) ENGINE = MergeTree() ORDER BY id");
                st.execute("INSERT INTO items VALUES (1)");
                ClickHouseDialect dialect = new ClickHouseDialect(DialectInitData.fromConnection(c));
                assertThat(dialect.supportsRoles()).as("supportsRoles").isTrue();

                execute(st, dialect.createRole("rt_role", false).orElseThrow());
                assertThat(rows(st, "SELECT name FROM system.roles WHERE name = 'rt_role'")).as("the role")
                        .containsExactly("rt_role");
                assertThatThrownBy(() -> st.execute(dialect.createRole("rt_role", false).orElseThrow()))
                        .as("a second one of the name").isInstanceOf(SQLException.class);
                execute(st, dialect.createRole("rt_role", true).orElseThrow());

                execute(st, dialect.grantTablePrivilege("SELECT", ITEMS, "rt_role", false));
                assertThat(String.join(" ", rows(st, "SHOW GRANTS FOR rt_role"))).as("a privilege of the role")
                        .contains("GRANT SELECT ON default.items TO rt_role");

                // a user that logs in, to read with what the role gives it
                execute(st, dialect.createUser("rt_user", false).orElseThrow());
                st.execute("ALTER USER rt_user IDENTIFIED WITH plaintext_password BY 'pw' HOST ANY");
                assertThatThrownBy(() -> select(url)).as("the user alone may not read")
                        .isInstanceOf(SQLException.class);

                execute(st, dialect.grantRole("rt_role", "rt_user", false));
                assertThat(String.join(" ", rows(st, "SHOW GRANTS FOR rt_user"))).as("the role granted to the user")
                        .contains("GRANT rt_role TO rt_user").doesNotContain("ADMIN OPTION");
                assertThat(select(url)).as("the user reads with the role's privilege").isEqualTo("1");

                execute(st, dialect.revokeRole("rt_role", "rt_user"));
                assertThat(String.join(" ", rows(st, "SHOW GRANTS FOR rt_user"))).as("revoked")
                        .doesNotContain("rt_role");
                assertThatThrownBy(() -> select(url)).as("the user no longer reads")
                        .isInstanceOf(SQLException.class);
                execute(st, dialect.grantRole("rt_role", "rt_user", true));
                assertThat(String.join(" ", rows(st, "SHOW GRANTS FOR rt_user"))).as("with the admin option")
                        .contains("GRANT rt_role TO rt_user WITH ADMIN OPTION");

                execute(st, dialect.dropRole("rt_role", false).orElseThrow());
                assertThat(rows(st, "SELECT name FROM system.roles WHERE name = 'rt_role'")).as("dropped")
                        .isEmpty();
                assertThat(String.join(" ", rows(st, "SHOW GRANTS FOR rt_user"))).as("the grant gone with the role")
                        .doesNotContain("rt_role");
                assertThatThrownBy(() -> select(url)).as("nor the privilege left to the user")
                        .isInstanceOf(SQLException.class);
                assertThatThrownBy(() -> st.execute(dialect.dropRole("rt_role", false).orElseThrow()))
                        .as("a missing one").isInstanceOf(SQLException.class);
                execute(st, dialect.dropRole("rt_role", true).orElseThrow());
                execute(st, dialect.dropUser("rt_user", true).orElseThrow());
            }
        }
    }

    /** The table as rt_user reads it, logged in anew, so that it holds its grants of now. */
    private static String select(String url) throws SQLException {
        try (Connection c = DriverManager.getConnection(url, "rt_user", "pw"); Statement st = c.createStatement();
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
