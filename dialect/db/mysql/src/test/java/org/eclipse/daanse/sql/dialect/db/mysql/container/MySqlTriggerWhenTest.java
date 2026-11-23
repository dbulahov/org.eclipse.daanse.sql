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
 * A trigger with a WHEN condition fires only for the rows the condition holds of. MySQL has no WHEN in
 * {@code CREATE TRIGGER}. The dialect is built from a live connection, and the trigger it writes with a condition must
 * be created on the server and fire as the condition says: before an insert with a body of its own, after an update
 * comparing OLD and NEW, and after a delete calling a procedure.
 */
@EnabledIfSystemProperty(named = "integration.docker", matches = "true")
class MySqlTriggerWhenTest {

    private static final TableReference ITEMS = new TableReference(
            Optional.of(new SchemaReference(Optional.empty(), "rt")), "items", TableReference.TYPE_TABLE);

    private static final List<String> SETUP = List.of("CREATE TABLE items(id INT, mark VARCHAR(10), qty INT)",
            "CREATE TABLE audit(op VARCHAR(10), item_id INT)");

    @ParameterizedTest(name = "mysql:{0}")
    @ValueSource(strings = { "5.7", "8.0", "8.4", "9" })
    void trigger_when(String tag) throws Exception {
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

                execute(st, dialect.createTrigger("items_big", TriggerTiming.BEFORE, TriggerEvent.INSERT, ITEMS,
                        TriggerScope.ROW, "NEW.qty > 10", "SET NEW.mark = 'big'"));
                st.execute("INSERT INTO items VALUES (1, NULL, 5), (2, NULL, 20)");
                assertThat(rows(st, "SELECT CONCAT(id, ':', IFNULL(mark, '-')) FROM items ORDER BY id"))
                        .as("BEFORE INSERT, where the condition holds only").containsExactly("1:-", "2:big");

                execute(st, dialect.createTrigger("items_audit", TriggerTiming.AFTER, TriggerEvent.UPDATE, ITEMS,
                        TriggerScope.ROW, "NOT (OLD.mark <=> NEW.mark)",
                        "INSERT INTO audit VALUES ('UPDATE', NEW.id)"));
                st.execute("UPDATE items SET mark = 'big' WHERE id = 2");
                assertThat(rows(st, "SELECT op FROM audit")).as("AFTER UPDATE, the mark the same: nothing").isEmpty();
                st.execute("UPDATE items SET mark = 'x' WHERE id IN (1, 2)");
                assertThat(rows(st, "SELECT CONCAT(op, ':', item_id) FROM audit ORDER BY item_id"))
                        .as("AFTER UPDATE, the mark changed, once per row").containsExactly("UPDATE:1", "UPDATE:2");

                st.execute("DELETE FROM audit");
                execute(st, dialect.createTriggerProcedure("log_delete", "rt",
                        "BEGIN INSERT INTO audit VALUES ('DELETE', NULL); END").orElseThrow());
                execute(st, dialect.createTriggerUsingProcedure("items_delete", "rt", TriggerTiming.AFTER,
                        TriggerEvent.DELETE, ITEMS, TriggerScope.ROW, "OLD.qty > 10", "log_delete"));
                st.execute("DELETE FROM items WHERE id = 1");
                assertThat(rows(st, "SELECT op FROM audit")).as("AFTER DELETE of a small one: no call").isEmpty();
                st.execute("DELETE FROM items WHERE id = 2");
                assertThat(rows(st, "SELECT op FROM audit")).as("AFTER DELETE of a big one: the procedure called")
                        .containsExactly("DELETE");
            }
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
