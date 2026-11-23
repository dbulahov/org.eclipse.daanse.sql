/*
 * Copyright (c) 2026 Contributors to the Eclipse Foundation.
 *
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.daanse.sql.dialect.db.mysql.container;

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
import org.eclipse.daanse.sql.dialect.db.mysql.MySqlDialect;
import org.eclipse.daanse.sql.model.schema.SchemaReference;
import org.eclipse.daanse.sql.model.schema.TableReference;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.testcontainers.containers.MySQLContainer;

/**
 * MySQL creates a user without a password, locked, with {@code CREATE USER [IF NOT EXISTS] x ACCOUNT LOCK}, and drops
 * it with {@code DROP USER [IF EXISTS] x}, from 5.7.8. The dialect is built from a live connection, its version with
 * its patch: from 5.7.8 on the user it creates is there, locked, cannot log in, takes a privilege and is dropped, and
 * IF [NOT] EXISTS spares an error; on 5.6 it writes no user, for the server would refuse the statement. The images of
 * 5.7.7 to 5.7.9 are no longer served, so 5.7 is its latest, 5.7.44: before a version kept its patch, every 5.7 was
 * taken for 5.7.0, and had no users.
 */
@EnabledIfSystemProperty(named = "integration.docker", matches = "true")
class MySqlUsersTest {

    private static final TableReference ITEMS = new TableReference(
            Optional.of(new SchemaReference(Optional.empty(), "rt")), "items", TableReference.TYPE_TABLE);

    @ParameterizedTest(name = "mysql:{0} -> users {1}")
    @CsvSource({ "5.6, false", "5.7, true", "8.0, true", "8.4, true", "9, true" })
    void users(String tag, boolean expected) throws Exception {
        // the server's own configuration: Testcontainers' default sets innodb_log_file_size, which MySQL 9 refuses
        try (MySQLContainer<?> my = new MySQLContainer<>("mysql:" + tag).withConfigurationOverride("mysql-conf")
                .withDatabaseName("rt").withUsername("rt").withPassword("rt")) {
            my.start();
            // root may create users and read mysql.user; the container's user may not
            try (Connection c = DriverManager.getConnection(my.getJdbcUrl(), "root", my.getPassword());
                    Statement st = c.createStatement()) {
                st.execute("CREATE TABLE items(id INT)");
                MySqlDialect dialect = new MySqlDialect(DialectInitData.fromConnection(c));

                assertThat(dialect.supportsUsers()).as("supportsUsers").isEqualTo(expected);

                if (!expected) {
                    assertThat(dialect.createUser("rt_user", true)).as("no user on 5.6").isEmpty();
                    assertThat(dialect.dropUser("rt_user", true)).as("no user on 5.6").isEmpty();
                    assertThatThrownBy(() -> st.execute("CREATE USER IF NOT EXISTS rt_user ACCOUNT LOCK"))
                            .as("5.6 knows neither IF NOT EXISTS nor ACCOUNT LOCK").isInstanceOf(SQLException.class)
                            .hasMessageContaining("syntax");
                    return;
                }

                st.execute(dialect.createUser("rt_user", false).orElseThrow());
                assertThat(rows(st, "SELECT CONCAT(user, '@', host, ':', account_locked) FROM mysql.user"
                        + " WHERE user = 'rt_user'")).as("the user, of any host, locked").containsExactly("rt_user@%:Y");

                assertThatThrownBy(() -> DriverManager.getConnection(my.getJdbcUrl(), "rt_user", "").close())
                        .as("a locked user without a password does not log in").isInstanceOf(SQLException.class);

                assertThatThrownBy(() -> st.execute(dialect.createUser("rt_user", false).orElseThrow()))
                        .as("a second one of the name").isInstanceOf(SQLException.class)
                        .hasMessageContaining("CREATE USER");
                st.execute(dialect.createUser("rt_user", true).orElseThrow());

                st.execute(dialect.grantTablePrivilege("SELECT", ITEMS, "rt_user", false));
                assertThat(String.join(" ", rows(st, "SHOW GRANTS FOR rt_user"))).as("a privilege of the user")
                        .contains("GRANT SELECT ON `rt`.`items`");

                st.execute(dialect.dropUser("rt_user", false).orElseThrow());
                assertThat(rows(st, "SELECT user FROM mysql.user WHERE user = 'rt_user'")).as("dropped").isEmpty();
                assertThatThrownBy(() -> st.execute(dialect.dropUser("rt_user", false).orElseThrow()))
                        .as("a missing one").isInstanceOf(SQLException.class).hasMessageContaining("DROP USER");
                st.execute(dialect.dropUser("rt_user", true).orElseThrow());
            }
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
