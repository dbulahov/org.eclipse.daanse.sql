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
import java.util.Optional;

import org.eclipse.daanse.sql.dialect.api.DialectInitData;
import org.eclipse.daanse.sql.dialect.db.mssqlserver.MicrosoftSqlServerDialect;
import org.eclipse.daanse.sql.model.schema.SchemaReference;
import org.eclipse.daanse.sql.model.schema.TableReference;
import org.eclipse.daanse.sql.model.schema.Trigger.TriggerEvent;
import org.eclipse.daanse.sql.model.schema.Trigger.TriggerScope;
import org.eclipse.daanse.sql.model.schema.Trigger.TriggerTiming;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.containers.MSSQLServerContainer;

/**
 * SQL Server runs a trigger procedure from a trigger of {@code AS EXEC proc}. The dialect is built from a live
 * connection: the procedure it writes is created, and created again replaced; the trigger it writes calls it after
 * an insert, an update and a delete. SQL Server has no {@code WHEN} on a trigger: the dialect refuses a condition, for
 * left out the trigger fired for every row it did not hold of too; written into the body over the deleted rows, it
 * calls the procedure only where it holds.
 */
@EnabledIfSystemProperty(named = "integration.docker", matches = "true")
class MsSqlTriggerProcedureTest {

    private static final TableReference ITEMS = new TableReference(
            Optional.of(new SchemaReference(Optional.empty(), "dbo")), "items", TableReference.TYPE_TABLE);

    @ParameterizedTest(name = "mssql/server:{0}")
    @ValueSource(strings = { "2017-latest", "2019-latest", "2022-latest", "2025-latest" })
    void triggerProcedure(String tag) throws Exception {
        try (MSSQLServerContainer<?> ms = new MSSQLServerContainer<>("mcr.microsoft.com/mssql/server:" + tag)
                .acceptLicense()) {
            ms.start();
            try (Connection c = DriverManager.getConnection(ms.getJdbcUrl(), ms.getUsername(), ms.getPassword());
                    Statement st = c.createStatement()) {
                MicrosoftSqlServerDialect dialect = new MicrosoftSqlServerDialect(DialectInitData.fromConnection(c));
                st.execute("CREATE TABLE items(id INT, qty INT)");
                st.execute("CREATE TABLE audit(op VARCHAR(10))");

                // the procedure, created and replaced
                String proc = dialect.createTriggerProcedure("log_it", "dbo",
                        "BEGIN INSERT INTO dbo.audit VALUES ('v1') END").orElseThrow();
                assertThat(proc).isEqualTo("CREATE OR ALTER PROCEDURE \"dbo\".\"log_it\" AS BEGIN INSERT INTO"
                        + " dbo.audit VALUES ('v1') END");
                st.execute(proc);
                st.execute(dialect.createTriggerProcedure("log_it", "dbo",
                        "BEGIN INSERT INTO dbo.audit VALUES ('call') END").orElseThrow());

                // after an insert, an update, a delete
                for (TriggerEvent event : List.of(TriggerEvent.INSERT, TriggerEvent.UPDATE, TriggerEvent.DELETE)) {
                    st.execute(dialect.createTriggerUsingProcedure("items_" + event.name().toLowerCase(), "dbo",
                            TriggerTiming.AFTER, event, ITEMS, TriggerScope.STATEMENT, null, "log_it"));
                }
                st.execute("INSERT INTO items VALUES (1, 5)");
                assertThat(rows(st, "SELECT op FROM audit")).as("after an insert, the replaced body")
                        .containsExactly("call");
                st.execute("UPDATE items SET qty = 6");
                st.execute("DELETE FROM items");
                assertThat(rows(st, "SELECT COUNT(*) FROM audit")).as("after an update and a delete")
                        .containsExactly("3");
                for (TriggerEvent event : List.of(TriggerEvent.INSERT, TriggerEvent.UPDATE, TriggerEvent.DELETE)) {
                    st.execute("DROP TRIGGER items_" + event.name().toLowerCase());
                }

                // a condition of the trigger: SQL Server has no WHEN, and the dialect refuses it rather than leave it
                // out, which fired the trigger for every row the condition did not hold of too
                assertThatThrownBy(() -> dialect.createTriggerUsingProcedure("items_big_delete", "dbo",
                        TriggerTiming.AFTER, TriggerEvent.DELETE, ITEMS, TriggerScope.ROW, "OLD.qty > 10", "log_it"))
                        .as("a condition, with a procedure").isInstanceOf(UnsupportedOperationException.class)
                        .hasMessageContaining("OLD.qty > 10");
                assertThatThrownBy(() -> dialect.createTrigger("items_big", TriggerTiming.AFTER, TriggerEvent.INSERT,
                        ITEMS, TriggerScope.ROW, "NEW.qty > 10", "AS INSERT INTO dbo.audit VALUES ('big')"))
                        .as("a condition, with a body").isInstanceOf(UnsupportedOperationException.class);

                // the condition written into the body, over the deleted rows, as the refusal says
                st.execute("DELETE FROM audit");
                st.execute("INSERT INTO items VALUES (1, 5), (2, 20)");
                st.execute(dialect.createTrigger("items_big_delete", TriggerTiming.AFTER, TriggerEvent.DELETE, ITEMS,
                        TriggerScope.STATEMENT, null,
                        "AS IF EXISTS (SELECT 1 FROM deleted WHERE qty > 10) EXEC dbo.log_it"));
                st.execute("DELETE FROM items WHERE id = 1");
                assertThat(rows(st, "SELECT COUNT(*) FROM audit")).as("a small one deleted: no call")
                        .containsExactly("0");
                st.execute("DELETE FROM items WHERE id = 2");
                assertThat(rows(st, "SELECT COUNT(*) FROM audit")).as("a big one deleted: the procedure called")
                        .containsExactly("1");
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
