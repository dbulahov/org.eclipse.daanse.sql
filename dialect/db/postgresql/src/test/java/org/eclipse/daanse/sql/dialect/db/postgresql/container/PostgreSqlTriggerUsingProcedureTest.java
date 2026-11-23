/*
 * Copyright (c) 2026 Contributors to the Eclipse Foundation.
 *
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.daanse.sql.dialect.db.postgresql.container;

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
import org.eclipse.daanse.sql.dialect.db.postgresql.PostgreSqlDialect;
import org.eclipse.daanse.sql.model.schema.SchemaReference;
import org.eclipse.daanse.sql.model.schema.TableReference;
import org.eclipse.daanse.sql.model.schema.Trigger.TriggerEvent;
import org.eclipse.daanse.sql.model.schema.Trigger.TriggerScope;
import org.eclipse.daanse.sql.model.schema.Trigger.TriggerTiming;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * A PostgreSQL trigger calls a trigger function: {@code EXECUTE FUNCTION f()} since 11, {@code EXECUTE PROCEDURE f()}
 * before. The dialect is built from a live connection, and the trigger function and the trigger it writes must fire on
 * the server: before a row is inserted, after a row is updated where the WHEN condition holds, once after a statement,
 * with a body replaced and a body that holds {@code $$}; and both are dropped again.
 */
@EnabledIfSystemProperty(named = "integration.docker", matches = "true")
class PostgreSqlTriggerUsingProcedureTest {

    private static final TableReference ITEMS = new TableReference(
            Optional.of(new SchemaReference(Optional.empty(), "public")), "items", TableReference.TYPE_TABLE);

    private static final String SETUP = """
            CREATE TABLE items(id INT, mark VARCHAR(10));
            CREATE TABLE audit(op VARCHAR(10), item_id INT);
            """;

    @ParameterizedTest(name = "postgres:{0} -> EXECUTE FUNCTION {1}")
    @CsvSource({ "9.6, false", "10-alpine, false", "11-alpine, true", "16-alpine, true" })
    void trigger_using_procedure(String tag, boolean executeFunction) throws Exception {
        try (PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:" + tag)) {
            pg.start();
            try (Connection c = pg.createConnection(""); Statement st = c.createStatement()) {
                st.execute(SETUP);
                PostgreSqlDialect dialect = new PostgreSqlDialect(DialectInitData.fromConnection(c));

                st.execute(procedure(dialect, "mark_row", "BEGIN NEW.mark := 'v1'; RETURN NEW; END"));
                String markTrigger = dialect.createTriggerUsingProcedure("items_mark", "public", TriggerTiming.BEFORE,
                        TriggerEvent.INSERT, ITEMS, TriggerScope.ROW, null, "mark_row");
                assertThat(markTrigger).contains(executeFunction ? "EXECUTE FUNCTION" : "EXECUTE PROCEDURE");
                st.execute(markTrigger);
                st.execute("INSERT INTO items(id) VALUES (1)");
                assertThat(rows(st, "SELECT mark FROM items WHERE id = 1")).as("BEFORE INSERT").containsExactly("v1");

                st.execute(procedure(dialect, "mark_row", "BEGIN NEW.mark := 'v2'; RETURN NEW; END"));
                st.execute("INSERT INTO items(id) VALUES (2)");
                assertThat(rows(st, "SELECT mark FROM items WHERE id = 2")).as("the replaced body")
                        .containsExactly("v2");

                st.execute(procedure(dialect, "audit_row",
                        "BEGIN INSERT INTO audit VALUES (TG_OP, NEW.id); RETURN NULL; END"));
                st.execute(dialect.createTriggerUsingProcedure("items_audit", "public", TriggerTiming.AFTER,
                        TriggerEvent.UPDATE, ITEMS, TriggerScope.ROW, "OLD.mark IS DISTINCT FROM NEW.mark",
                        "audit_row"));
                st.execute("UPDATE items SET mark = 'v1' WHERE id = 1");
                assertThat(rows(st, "SELECT op FROM audit")).as("WHEN does not hold, nothing logged").isEmpty();
                st.execute("UPDATE items SET mark = 'x' WHERE id IN (1, 2)");
                assertThat(rows(st, "SELECT op || ':' || item_id FROM audit ORDER BY item_id"))
                        .as("AFTER UPDATE, once per row").containsExactly("UPDATE:1", "UPDATE:2");

                st.execute("DELETE FROM audit");
                st.execute(procedure(dialect, "audit_statement",
                        "BEGIN INSERT INTO audit VALUES (TG_OP, NULL); RETURN NULL; END"));
                st.execute(dialect.createTriggerUsingProcedure("items_delete", "public", TriggerTiming.AFTER,
                        TriggerEvent.DELETE, ITEMS, TriggerScope.STATEMENT, null, "audit_statement"));
                st.execute("DELETE FROM items WHERE id IN (1, 2)");
                assertThat(rows(st, "SELECT op FROM audit")).as("AFTER DELETE, once for the statement")
                        .containsExactly("DELETE");

                st.execute(procedure(dialect, "mark_row", "BEGIN NEW.mark := $$dollar$$; RETURN NEW; END"));
                st.execute("INSERT INTO items(id) VALUES (3)");
                assertThat(rows(st, "SELECT mark FROM items WHERE id = 3")).as("a body that holds $$")
                        .containsExactly("dollar");

                for (String sql : dialect.dropTriggerOnTable("items_mark", ITEMS, false)) {
                    st.execute(sql);
                }
                st.execute("INSERT INTO items(id) VALUES (4)");
                assertThat(rows(st, "SELECT mark FROM items WHERE id = 4")).as("the trigger dropped")
                        .containsExactly((String) null);
                for (String sql : dialect.dropTriggerOnTable("items_mark", ITEMS, true)) {
                    st.execute(sql);
                }

                st.execute(dialect.dropProcedure("mark_row", "public", false).orElseThrow());
                assertThatThrownBy(() -> rows(st, "SELECT 'public.mark_row()'::regprocedure"))
                        .as("the trigger function dropped").isInstanceOf(SQLException.class)
                        .hasMessageContaining("does not exist");
                st.execute(dialect.dropProcedure("mark_row", "public", true).orElseThrow());
            }
        }
    }

    private static String procedure(PostgreSqlDialect dialect, String name, String body) {
        return dialect.createTriggerProcedure(name, "public", body).orElseThrow();
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
