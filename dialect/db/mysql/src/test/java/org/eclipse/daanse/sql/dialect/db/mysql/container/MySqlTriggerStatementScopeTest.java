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
import org.eclipse.daanse.sql.model.schema.Trigger.TriggerEvent;
import org.eclipse.daanse.sql.model.schema.Trigger.TriggerScope;
import org.eclipse.daanse.sql.model.schema.Trigger.TriggerTiming;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.containers.MySQLContainer;

/**
 * MySQL and MariaDB have row triggers only, {@code FOR EACH ROW}: {@code FOR EACH STATEMENT} is a syntax error, and a
 * trigger that fires once for a statement cannot be written with a row trigger. The dialect is built from a live
 * connection, and it must write no statement trigger, as it writes nothing it cannot ({@code UnsupportedOperationException}),
 * by its own body or calling a procedure, while a row trigger is created and fires once per row.
 */
@EnabledIfSystemProperty(named = "integration.docker", matches = "true")
class MySqlTriggerStatementScopeTest {

    private static final TableReference ITEMS = new TableReference(
            Optional.of(new SchemaReference(Optional.empty(), "rt")), "items", TableReference.TYPE_TABLE);

    private static final List<String> SETUP = List.of("CREATE TABLE items(id INT)",
            "CREATE TABLE audit(op VARCHAR(10))");

    @ParameterizedTest(name = "mysql:{0}")
    @ValueSource(strings = { "5.7", "8.0", "8.4", "9" })
    void statement_scope(String tag) throws Exception {
        // the server's own configuration: Testcontainers' default sets innodb_log_file_size, which MySQL 9 refuses
        try (MySQLContainer<?> my = new MySQLContainer<>("mysql:" + tag).withConfigurationOverride("mysql-conf")
                .withDatabaseName("rt").withUsername("rt").withPassword("rt")) {
            my.start();
            try (Connection c = DriverManager.getConnection(my.getJdbcUrl(), my.getUsername(), my.getPassword());
                    Statement st = c.createStatement()) {
                for (String sql : SETUP) {
                    st.execute(sql);
                }
                MySqlDialect dialect = new MySqlDialect(DialectInitData.fromConnection(c));

                assertThatThrownBy(() -> st.execute(
                        "CREATE TRIGGER rt.t AFTER INSERT ON rt.items FOR EACH STATEMENT INSERT INTO audit VALUES ('x')"))
                        .as("the server knows no FOR EACH STATEMENT").isInstanceOf(SQLException.class)
                        .hasMessageContaining("syntax");

                for (TriggerTiming timing : List.of(TriggerTiming.BEFORE, TriggerTiming.AFTER)) {
                    for (TriggerEvent event : TriggerEvent.values()) {
                        assertThatThrownBy(() -> dialect.createTrigger("items_stmt", timing, event, ITEMS,
                                TriggerScope.STATEMENT, null, "INSERT INTO audit VALUES ('x')"))
                                .as(timing + " " + event + " FOR EACH STATEMENT")
                                .isInstanceOf(UnsupportedOperationException.class);
                        assertThatThrownBy(() -> dialect.createTrigger("items_stmt", timing, event, ITEMS,
                                TriggerScope.STATEMENT, "1 = 1", "INSERT INTO audit VALUES ('x')"))
                                .as(timing + " " + event + " FOR EACH STATEMENT with a condition")
                                .isInstanceOf(UnsupportedOperationException.class);
                    }
                }
                st.execute(dialect.createTriggerProcedure("log_it", "rt",
                        "BEGIN INSERT INTO audit VALUES ('call'); END").orElseThrow());
                assertThatThrownBy(() -> dialect.createTriggerUsingProcedure("items_stmt", "rt", TriggerTiming.AFTER,
                        TriggerEvent.DELETE, ITEMS, TriggerScope.STATEMENT, null, "log_it"))
                        .as("calling a procedure FOR EACH STATEMENT").isInstanceOf(UnsupportedOperationException.class);

                st.execute(dialect.createTrigger("items_row", TriggerTiming.AFTER, TriggerEvent.INSERT, ITEMS,
                        TriggerScope.ROW, null, "INSERT INTO audit VALUES ('row')"));
                st.execute("INSERT INTO items VALUES (1), (2)");
                assertThat(rows(st, "SELECT op FROM audit")).as("a row trigger, once per row")
                        .containsExactly("row", "row");
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
