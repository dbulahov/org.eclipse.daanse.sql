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
 * MySQL has roles from 8.0: {@code CREATE ROLE [IF NOT EXISTS]}, {@code DROP ROLE [IF EXISTS]}, a role granted to a
 * user, with the admin option too, and revoked. The dialect is built from a live connection: from 8.0 on the role it
 * creates is there, holds a privilege, gives it to the user it is granted to and is dropped, and IF [NOT] EXISTS spares
 * an error; on 5.7 it writes no role, for the server would refuse the statement.
 */
@EnabledIfSystemProperty(named = "integration.docker", matches = "true")
class MySqlRolesTest {

    private static final TableReference ITEMS = new TableReference(
            Optional.of(new SchemaReference(Optional.empty(), "rt")), "items", TableReference.TYPE_TABLE);

    @ParameterizedTest(name = "mysql:{0} -> roles {1}")
    @CsvSource({ "5.7, false", "8.0, true", "8.4, true", "9, true" })
    void roles(String tag, boolean expected) throws Exception {
        // the server's own configuration: Testcontainers' default sets innodb_log_file_size, which MySQL 9 refuses
        try (MySQLContainer<?> my = new MySQLContainer<>("mysql:" + tag).withConfigurationOverride("mysql-conf")
                .withDatabaseName("rt").withUsername("rt").withPassword("rt")) {
            my.start();
            // root may create roles and users and read mysql.user; the container's user may not
            try (Connection c = DriverManager.getConnection(my.getJdbcUrl(), "root", my.getPassword());
                    Statement st = c.createStatement()) {
                st.execute("CREATE TABLE items(id INT)");
                MySqlDialect dialect = new MySqlDialect(DialectInitData.fromConnection(c));

                assertThat(dialect.supportsRoles()).as("supportsRoles").isEqualTo(expected);

                if (!expected) {
                    assertThat(dialect.createRole("rt_role", false)).as("no role on 5.7").isEmpty();
                    assertThat(dialect.createRole("rt_role", true)).as("no role on 5.7").isEmpty();
                    assertThat(dialect.dropRole("rt_role", true)).as("no role on 5.7").isEmpty();
                    assertThatThrownBy(() -> st.execute("CREATE ROLE rt_role"))
                            .as("MySQL 5.7 knows no CREATE ROLE").isInstanceOf(SQLException.class)
                            .hasMessageContaining("syntax");
                    return;
                }

                st.execute(dialect.createRole("rt_role", false).orElseThrow());
                assertThat(rows(st, "SELECT CONCAT(user, '@', host) FROM mysql.user WHERE user = 'rt_role'"))
                        .as("the role").containsExactly("rt_role@%");
                assertThatThrownBy(() -> st.execute(dialect.createRole("rt_role", false).orElseThrow()))
                        .as("a second one of the name").isInstanceOf(SQLException.class)
                        .hasMessageContaining("CREATE ROLE");
                st.execute(dialect.createRole("rt_role", true).orElseThrow());

                st.execute(dialect.grantTablePrivilege("SELECT", ITEMS, "rt_role", false));
                st.execute(dialect.createUser("rt_user", false).orElseThrow());
                st.execute(dialect.grantRole("rt_role", "rt_user", false));
                assertThat(String.join(" ", rows(st, "SHOW GRANTS FOR rt_user"))).as("the role granted to the user")
                        .contains("GRANT `rt_role`@`%` TO `rt_user`@`%`");
                assertThat(String.join(" ", rows(st, "SHOW GRANTS FOR rt_user USING rt_role")))
                        .as("the user holds the role's privilege").contains("GRANT SELECT ON `rt`.`items`");

                st.execute(dialect.revokeRole("rt_role", "rt_user"));
                assertThat(String.join(" ", rows(st, "SHOW GRANTS FOR rt_user"))).as("revoked")
                        .doesNotContain("rt_role");
                st.execute(dialect.grantRole("rt_role", "rt_user", true));
                assertThat(String.join(" ", rows(st, "SHOW GRANTS FOR rt_user"))).as("with the admin option")
                        .contains("GRANT `rt_role`@`%` TO `rt_user`@`%` WITH ADMIN OPTION");

                st.execute(dialect.dropRole("rt_role", false).orElseThrow());
                assertThat(rows(st, "SELECT user FROM mysql.user WHERE user = 'rt_role'")).as("dropped").isEmpty();
                assertThat(String.join(" ", rows(st, "SHOW GRANTS FOR rt_user"))).as("the grant gone with the role")
                        .doesNotContain("rt_role");
                assertThatThrownBy(() -> st.execute(dialect.dropRole("rt_role", false).orElseThrow()))
                        .as("a missing one").isInstanceOf(SQLException.class).hasMessageContaining("DROP ROLE");
                st.execute(dialect.dropRole("rt_role", true).orElseThrow());
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
