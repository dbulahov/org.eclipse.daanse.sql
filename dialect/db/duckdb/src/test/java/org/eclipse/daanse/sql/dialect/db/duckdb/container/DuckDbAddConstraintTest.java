/*
 * Copyright (c) 2026 Contributors to the Eclipse Foundation.
 *
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.daanse.sql.dialect.db.duckdb.container;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.JDBCType;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

import org.eclipse.daanse.sql.dialect.api.generator.DdlGenerator;
import org.eclipse.daanse.sql.dialect.db.duckdb.DuckDbDialect;
import org.eclipse.daanse.sql.jdbc.record.schema.ColumnDefinitionRecord;
import org.eclipse.daanse.sql.jdbc.record.schema.ColumnMetaDataRecord;
import org.eclipse.daanse.sql.jdbc.record.schema.PrimaryKeyRecord;
import org.eclipse.daanse.sql.model.schema.ColumnDefinition;
import org.eclipse.daanse.sql.model.schema.ColumnMetaData;
import org.eclipse.daanse.sql.model.schema.ColumnReference;
import org.eclipse.daanse.sql.model.schema.SchemaReference;
import org.eclipse.daanse.sql.model.schema.TableReference;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * DuckDB adds no foreign key and no check to an existing table, in any version from 1.0 on, and adds a unique
 * constraint only from 2.0 on, and then only while no other table refers to the table; so the dialect adds none of
 * them later and declares them in {@code CREATE TABLE}. The CLI of each version runs what the dialect writes, in
 * Docker, and the dialect is built for the version the CLI reports: the {@code CREATE TABLE} with a unique constraint,
 * a check and a foreign key holds the rows that keep them and refuses those that break them; a foreign key keeps only
 * the actions DuckDB knows, RESTRICT and NO ACTION, and leaves CASCADE out, which the server refuses; the
 * {@code ALTER TABLE … ADD} forms are refused, the unique one from 2.0 on only with a table that refers to it.
 */
@EnabledIfSystemProperty(named = "integration.docker", matches = "true")
class DuckDbAddConstraintTest {

    private static final Optional<SchemaReference> MAIN = Optional.of(new SchemaReference(Optional.empty(), "main"));
    private static final TableReference CUSTOMER = new TableReference(MAIN, "customer");
    private static final TableReference ORDERS = new TableReference(MAIN, "orders");

    @ParameterizedTest(name = "duckdb:{0} -> ADD UNIQUE later {1}")
    @CsvSource({ "1.0.0, false", "1.1.3, false", "1.2.2, false", "1.3.2, false", "1.4.4, false", "1.5.6, false",
            "2.0.0, true", "2.0.1, true" })
    void addConstraint(String tag, boolean uniqueLater) {
        DuckDbDialect dialect = DuckDbCli.dialect(tag);
        DdlGenerator ddl = dialect.ddlGenerator();

        assertThat(ddl.supportsAddForeignKeyConstraint()).as("supportsAddForeignKeyConstraint").isFalse();
        assertThat(ddl.supportsAddUniqueConstraint()).as("supportsAddUniqueConstraint").isFalse();
        assertThat(ddl.supportsAddCheckConstraint()).as("supportsAddCheckConstraint").isFalse();

        // declared in CREATE TABLE, the constraints hold
        String customer = ddl.createTable(CUSTOMER, List.of(column(CUSTOMER, "id", JDBCType.INTEGER),
                column(CUSTOMER, "email", JDBCType.VARCHAR), column(CUSTOMER, "n", JDBCType.INTEGER)),
                pk(CUSTOMER),
                List.of(ddl.uniqueConstraintClause("uc_email", List.of("email")),
                        ddl.checkConstraintClause("ck_n", "\"n\" > 0")),
                false);
        String fk = ddl.foreignKeyClause("fk_orders_customer", List.of("cid"), CUSTOMER, List.of("id"), "RESTRICT",
                "NO ACTION");
        assertThat(fk).endsWith(" ON DELETE RESTRICT ON UPDATE NO ACTION");
        String orders = ddl.createTable(ORDERS, List.of(column(ORDERS, "id", JDBCType.INTEGER),
                column(ORDERS, "cid", JDBCType.INTEGER)), pk(ORDERS), List.of(fk), false);
        String setup = customer + "; " + orders + "; INSERT INTO customer VALUES (1, 'a@x', 1), (2, 'b@x', 2);"
                + " INSERT INTO orders VALUES (10, 1), (11, NULL);";

        DuckDbCli.Result kept = DuckDbCli.run(tag, setup + " SELECT 'customers ' || count(*) FROM customer;"
                + " SELECT 'orders ' || count(*) FROM orders;");
        assertThat(kept.failed()).as(kept.err() + " in " + customer + "; " + orders).isFalse();
        assertThat(kept.out()).as("the rows that keep the constraints").containsExactly("customers 2", "orders 2");

        assertRefused(tag, setup + " INSERT INTO customer VALUES (3, 'a@x', 3);", "Constraint Error",
                "a second email");
        assertRefused(tag, setup + " INSERT INTO customer VALUES (3, 'c@x', 0);", "Constraint Error",
                "n not above 0");
        assertRefused(tag, setup + " INSERT INTO orders VALUES (12, 99);", "Constraint Error",
                "an order of no customer");
        assertRefused(tag, setup + " DELETE FROM customer WHERE id = 1;", "Constraint Error",
                "a customer an order refers to");

        // CASCADE is left out, which the server refuses
        String cascade = ddl.foreignKeyClause("fk_c", List.of("cid"), CUSTOMER, List.of("id"), "CASCADE", "SET NULL");
        assertThat(cascade).doesNotContain(" ON DELETE").doesNotContain(" ON UPDATE");
        DuckDbCli.Result withoutCascade = DuckDbCli.run(tag, customer + "; CREATE TABLE r(id INTEGER, cid INTEGER, "
                + cascade + "); SELECT 'ok';");
        assertThat(withoutCascade.failed()).as(withoutCascade.err() + " in " + cascade).isFalse();
        assertRefused(tag, customer + "; CREATE TABLE r(cid INTEGER REFERENCES customer(id) ON DELETE CASCADE);",
                "CASCADE", "ON DELETE CASCADE");

        // ALTER TABLE … ADD: no foreign key and no check in any version
        String plain = "CREATE TABLE customer(id INTEGER PRIMARY KEY, email VARCHAR, n INTEGER);"
                + " CREATE TABLE orders(id INTEGER, cid INTEGER);";
        assertRefused(tag, plain + " " + ddl.addForeignKeyConstraint(ORDERS, "fk_orders_customer", List.of("cid"),
                CUSTOMER, List.of("id"), null, null) + ";", "Not implemented", "ADD FOREIGN KEY");
        assertRefused(tag, plain + " " + ddl.addCheckConstraint(CUSTOMER, "ck_n", "\"n\" > 0") + ";",
                "Not implemented", "ADD CHECK");

        // ALTER TABLE … ADD UNIQUE: from 2.0 on, and then only while no table refers to it
        String addUnique = ddl.addUniqueConstraint(CUSTOMER, "uc_email", List.of("email")) + ";";
        String referring = " CREATE TABLE r(cid INTEGER REFERENCES customer(id));";
        if (uniqueLater) {
            DuckDbCli.Result added = DuckDbCli.run(tag, plain + " INSERT INTO customer VALUES (1, 'a@x', 1); "
                    + addUnique + " SELECT 'added';");
            assertThat(added.failed()).as(added.err()).isFalse();
            assertRefused(tag, plain + " " + addUnique + " INSERT INTO customer VALUES (1, 'a@x', 1), (2, 'a@x', 2);",
                    "Constraint Error", "the unique constraint added later holds");
            assertRefused(tag, plain + referring + " " + addUnique, "Dependency Error",
                    "ADD UNIQUE to a table another refers to");
        } else {
            assertRefused(tag, plain + " " + addUnique, "Not implemented", "ADD UNIQUE before 2.0");
        }
    }

    private static void assertRefused(String tag, String sql, String error, String what) {
        DuckDbCli.Result r = DuckDbCli.run(tag, sql);
        assertThat(r.failed()).as(what + " is refused: " + sql).isTrue();
        assertThat(r.err()).as(what).contains(error);
    }

    private static PrimaryKeyRecord pk(TableReference table) {
        return new PrimaryKeyRecord(table, List.of(new ColumnReference(Optional.of(table), "id")), Optional.empty());
    }

    private static ColumnDefinition column(TableReference table, String name, JDBCType type) {
        return new ColumnDefinitionRecord(new ColumnReference(Optional.of(table), name),
                new ColumnMetaDataRecord(type, type.getName(),
                        type == JDBCType.VARCHAR ? OptionalInt.of(50) : OptionalInt.empty(), OptionalInt.empty(),
                        OptionalInt.empty(), ColumnMetaData.Nullability.NULLABLE, OptionalInt.empty(),
                        Optional.empty(), Optional.empty(), ColumnMetaData.AutoIncrement.UNKNOWN,
                        ColumnMetaData.GeneratedColumn.UNKNOWN));
    }
}
