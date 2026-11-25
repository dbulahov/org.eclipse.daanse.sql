/*
 * Copyright (c) 2026 Contributors to the Eclipse Foundation.
 *
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.daanse.sql.dialect.db.sqlite.container;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;

import org.eclipse.daanse.sql.dialect.api.generator.DdlGenerator;
import org.eclipse.daanse.sql.dialect.db.sqlite.SqliteDialect;
import org.eclipse.daanse.sql.model.schema.SchemaReference;
import org.eclipse.daanse.sql.model.schema.TableReference;
import org.eclipse.daanse.sql.model.schema.Trigger.TriggerEvent;
import org.eclipse.daanse.sql.model.schema.Trigger.TriggerScope;
import org.eclipse.daanse.sql.model.schema.Trigger.TriggerTiming;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * SQLite has row triggers with WHEN in every version, and no {@code FOR EACH STATEMENT} (a syntax error). SQLite is
 * embedded: the CLI of each version runs the statements in Docker, and the dialect is built for the version the CLI
 * reports. The triggers the dialect writes, on a table named with its schema, must fire once per row, where the WHEN
 * condition holds only, read NEW and OLD, BEFORE, AFTER and INSTEAD OF on a view, and be dropped, IF EXISTS sparing an
 * error; a statement trigger it does not write, for a row trigger would not fire once for a statement.
 */
@EnabledIfSystemProperty(named = "integration.docker", matches = "true")
class SqliteTriggerTest {

    private static final Optional<SchemaReference> MAIN = Optional.of(new SchemaReference(Optional.empty(), "main"));
    private static final TableReference T = new TableReference(MAIN, "t", TableReference.TYPE_TABLE);
    private static final TableReference V = new TableReference(MAIN, "v", TableReference.TYPE_VIEW);

    private static final String SETUP = """
            CREATE TABLE t(id INTEGER PRIMARY KEY, a INTEGER);
            CREATE TABLE log(what TEXT, x INTEGER);
            CREATE VIEW v AS SELECT id, a FROM t;
            """;

    @ParameterizedTest(name = "sqlite {0}")
    @ValueSource(strings = { "3.22.0", "3.32.1", "3.46.1", "3.51.0" })
    void triggers(String version) {
        SqliteDialect d = SqliteCli.dialect(version);
        DdlGenerator ddl = d.ddlGenerator();

        String insert = ddl.createTrigger("tr_ins", TriggerTiming.AFTER, TriggerEvent.INSERT, T, TriggerScope.ROW,
                "NEW.a > 3", "BEGIN INSERT INTO log VALUES ('ins', NEW.a); END");
        assertThat(insert).contains(" FOR EACH ROW WHEN (NEW.a > 3) ");
        String update = ddl.createTrigger("tr_upd", TriggerTiming.BEFORE, TriggerEvent.UPDATE, T, TriggerScope.ROW,
                null, "BEGIN INSERT INTO log VALUES ('upd', OLD.a * 100 + NEW.a); END");
        String delete = ddl.createTrigger("tr_del", TriggerTiming.AFTER, TriggerEvent.DELETE, T, TriggerScope.ROW,
                null, "BEGIN INSERT INTO log VALUES ('del', OLD.id); END");
        String instead = ddl.createTrigger("tr_v", TriggerTiming.INSTEAD_OF, TriggerEvent.INSERT, V,
                TriggerScope.ROW, null, "BEGIN INSERT INTO t VALUES (NEW.id, NEW.a * 10); END");
        assertThat(instead).contains(" INSTEAD OF INSERT ON ");

        String sql = SETUP + insert + ";\n" + update + ";\n" + delete + ";\n" + instead + ";\n"
                + "INSERT INTO t VALUES (1, 5), (2, 1), (3, 7);\n" // the WHEN holds for 5 and 7
                + "UPDATE t SET a = a + 1 WHERE id IN (1, 2);\n" // a row each: 506, 102
                + "DELETE FROM t WHERE id = 3;\n"
                + "INSERT INTO v VALUES (4, 2);\n" // instead of the view, 20 into t; the WHEN holds for it
                + "SELECT 'log|' || group_concat(what || ':' || x, ',') FROM (SELECT * FROM log ORDER BY rowid);\n"
                + "SELECT 't|' || group_concat(id || ':' || a, ',') FROM (SELECT * FROM t ORDER BY id);\n"
                + ddl.dropTrigger("tr_ins", false) + ";\n" + ddl.dropTrigger("tr_ins", true) + ";\n"
                + "INSERT INTO t VALUES (5, 9);\n"
                + "SELECT 'after drop|' || count(*) FROM log WHERE what = 'ins';\n";
        SqliteCli.Result r = SqliteCli.run(version, sql);
        assertThat(r.failed()).as(r.err() + " in " + sql).isFalse();
        assertThat(r.out()).containsExactly(
                "log|ins:5,ins:7,upd:506,upd:102,del:3,ins:20",
                "t|1:6,2:2,4:20",
                "after drop|3");

        assertThatThrownBy(() -> ddl.createTrigger("tr_s", TriggerTiming.AFTER, TriggerEvent.INSERT, T,
                TriggerScope.STATEMENT, null, "BEGIN INSERT INTO log VALUES ('s', 0); END"))
                .as("no statement trigger").isInstanceOf(UnsupportedOperationException.class);
        SqliteCli.Result statement = SqliteCli.run(version, SETUP + "CREATE TRIGGER tr_s AFTER INSERT ON t"
                + " FOR EACH STATEMENT BEGIN INSERT INTO log VALUES ('s', 0); END;");
        assertThat(statement.failed()).as("the server refuses FOR EACH STATEMENT").isTrue();
        assertThat(statement.err()).contains("syntax error");

        SqliteCli.Result twice = SqliteCli.run(version, SETUP + insert + ";\n"
                + ddl.createTrigger("tr_ins", TriggerTiming.AFTER, TriggerEvent.INSERT, T, TriggerScope.ROW, null,
                        "BEGIN INSERT INTO log VALUES ('again', NEW.a); END", true) + ";");
        assertThat(twice.failed()).as("no OR REPLACE: a second one of the name fails").isTrue();
        assertThat(twice.err()).contains("already exists");
    }
}
