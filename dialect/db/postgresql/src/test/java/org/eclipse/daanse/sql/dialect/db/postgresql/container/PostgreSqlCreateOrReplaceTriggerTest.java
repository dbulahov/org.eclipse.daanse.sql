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
import java.util.Optional;

import org.eclipse.daanse.sql.dialect.api.DialectInitData;
import org.eclipse.daanse.sql.dialect.db.postgresql.PostgreSqlDialect;
import org.eclipse.daanse.sql.model.schema.SchemaReference;
import org.eclipse.daanse.sql.model.schema.TableReference;
import org.eclipse.daanse.sql.model.schema.Trigger;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * {@code CREATE OR REPLACE TRIGGER} came with PostgreSQL 14. The dialect is built from a live connection and must say
 * so: from 14 on its trigger replaces the one before, on 13 it writes a plain {@code CREATE TRIGGER}, and the server
 * would refuse the {@code OR REPLACE} form.
 */
//@EnabledIfSystemProperty(named = "integration.docker", matches = "true")
class PostgreSqlCreateOrReplaceTriggerTest {

    private static final TableReference ITEMS = new TableReference(
            Optional.of(new SchemaReference(Optional.empty(), "public")), "items", TableReference.TYPE_TABLE);

    private static final String SETUP = """
            CREATE TABLE items(id INT, mark VARCHAR(10));
            CREATE FUNCTION mark_v1() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN NEW.mark := 'v1'; RETURN NEW; END $$;
            CREATE FUNCTION mark_v2() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN NEW.mark := 'v2'; RETURN NEW; END $$;
            """;

    @ParameterizedTest(name = "postgres:{0} -> {1}")
    @CsvSource({ "13-alpine, false", "14-alpine, true", "16-alpine, true" })
    void create_or_replace_trigger(String tag, boolean expected) throws Exception {
        try (PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:" + tag)) {
            pg.start();
            try (Connection c = pg.createConnection(""); Statement st = c.createStatement()) {
                st.execute(SETUP);
                PostgreSqlDialect dialect = new PostgreSqlDialect(DialectInitData.fromConnection(c));

                assertThat(dialect.supportsCreateOrReplaceTrigger()).isEqualTo(expected);

                String first = trigger(dialect, "mark_v1");
                String second = trigger(dialect, "mark_v2");
                assertThat(first.startsWith("CREATE OR REPLACE TRIGGER")).isEqualTo(expected);

                st.execute(first);
                assertThat(markOfInsert(st, 1)).isEqualTo("v1");

                if (expected) {
                    st.execute(second);
                    assertThat(markOfInsert(st, 2)).as("the second trigger replaced the first").isEqualTo("v2");
                } else {
                    assertThatThrownBy(() -> st.execute(second)).as("a plain CREATE TRIGGER of the same name")
                            .isInstanceOf(SQLException.class).hasMessageContaining("already exists");
                    String orReplace = second.replaceFirst("^CREATE TRIGGER", "CREATE OR REPLACE TRIGGER");
                    assertThatThrownBy(() -> st.execute(orReplace)).as("PostgreSQL 13 knows no OR REPLACE")
                            .isInstanceOf(SQLException.class).hasMessageContaining("syntax error");
                }
            }
        }
    }

    private static String trigger(PostgreSqlDialect dialect, String function) {
        return dialect.createTrigger("items_mark", Trigger.TriggerTiming.BEFORE, Trigger.TriggerEvent.INSERT, ITEMS,
                Trigger.TriggerScope.ROW, null, "EXECUTE FUNCTION " + function + "()", true);
    }

    private static String markOfInsert(Statement st, int id) throws SQLException {
        st.execute("INSERT INTO items(id) VALUES (" + id + ")");
        try (ResultSet rs = st.executeQuery("SELECT mark FROM items WHERE id = " + id)) {
            assertThat(rs.next()).isTrue();
            return rs.getString(1);
        }
    }
}
