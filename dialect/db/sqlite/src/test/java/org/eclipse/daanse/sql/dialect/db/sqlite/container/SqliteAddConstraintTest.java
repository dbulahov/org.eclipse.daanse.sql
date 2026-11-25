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

import java.sql.JDBCType;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

import org.eclipse.daanse.sql.dialect.api.generator.DdlGenerator;
import org.eclipse.daanse.sql.dialect.db.sqlite.SqliteDialect;
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
import org.junit.jupiter.params.provider.ValueSource;

/**
 * SQLite adds no unique constraint, no check and no foreign key to an existing table, in any version (a syntax error
 * near CONSTRAINT): the dialect adds none of them later and declares them in {@code CREATE TABLE}. SQLite is embedded:
 * the CLI of each version runs what the dialect writes, in Docker, and the dialect is built for the version the CLI
 * reports. The {@code CREATE TABLE} with a unique constraint, a check and a foreign key holds the rows that keep them
 * and refuses those that break them; the foreign key refers to its table by its name alone, as SQLite asks of a table
 * named with its schema, keeps ON DELETE CASCADE, and is kept only where the connection turns foreign keys on.
 */
@EnabledIfSystemProperty(named = "integration.docker", matches = "true")
class SqliteAddConstraintTest {

    private static final Optional<SchemaReference> MAIN = Optional.of(new SchemaReference(Optional.empty(), "main"));
    private static final TableReference CUSTOMER = new TableReference(MAIN, "customer");
    private static final TableReference ORDERS = new TableReference(MAIN, "orders");

    private static final String FOREIGN_KEYS = "PRAGMA foreign_keys = ON;\n";

    @ParameterizedTest(name = "sqlite {0}")
    @ValueSource(strings = { "3.22.0", "3.25.3", "3.32.1", "3.40.1", "3.46.1", "3.51.0" })
    void addConstraint(String version) {
        SqliteDialect dialect = SqliteCli.dialect(version);
        DdlGenerator ddl = dialect.ddlGenerator();

        assertThat(ddl.supportsAddForeignKeyConstraint()).as("supportsAddForeignKeyConstraint").isFalse();
        assertThat(ddl.supportsAddUniqueConstraint()).as("supportsAddUniqueConstraint").isFalse();
        assertThat(ddl.supportsAddCheckConstraint()).as("supportsAddCheckConstraint").isFalse();
        assertThat(ddl.addForeignKeyConstraint(ORDERS, "fk", List.of("cid"), CUSTOMER, List.of("id"), null, null,
                DdlGenerator.Deferrability.NOT_DEFERRABLE)).as("no foreign key added later").isEmpty();

        // declared in CREATE TABLE, the constraints hold
        String customer = ddl.createTable(CUSTOMER, List.of(column(CUSTOMER, "id", JDBCType.INTEGER),
                column(CUSTOMER, "email", JDBCType.VARCHAR), column(CUSTOMER, "n", JDBCType.INTEGER)),
                pk(CUSTOMER),
                List.of(ddl.uniqueConstraintClause("uc_email", List.of("email")),
                        ddl.checkConstraintClause("ck_n", "\"n\" > 0")),
                false);
        String fk = ddl.foreignKeyClause("fk_orders_customer", List.of("cid"), CUSTOMER, List.of("id"), "CASCADE",
                "NO ACTION");
        assertThat(fk).as("the referenced table by its name alone").contains("REFERENCES \"customer\" (")
                .doesNotContain("\"main\".").endsWith(" ON DELETE CASCADE ON UPDATE NO ACTION");
        String orders = ddl.createTable(ORDERS, List.of(column(ORDERS, "id", JDBCType.INTEGER),
                column(ORDERS, "cid", JDBCType.INTEGER)), pk(ORDERS), List.of(fk), false);
        String setup = customer + ";\n" + orders + ";\nINSERT INTO customer VALUES (1, 'a@x', 1), (2, 'b@x', 2);\n"
                + "INSERT INTO orders VALUES (10, 1), (11, NULL), (12, 2);\n";

        SqliteCli.Result kept = SqliteCli.run(version, FOREIGN_KEYS + setup
                + "SELECT 'customers ' || count(*) FROM customer; SELECT 'orders ' || count(*) FROM orders;");
        assertThat(kept.failed()).as(kept.err() + " in " + setup).isFalse();
        assertThat(kept.out()).as("the rows that keep the constraints").containsExactly("customers 2", "orders 3");

        assertRefused(version, FOREIGN_KEYS + setup + "INSERT INTO customer VALUES (3, 'a@x', 3);", "UNIQUE",
                "a second email");
        assertRefused(version, FOREIGN_KEYS + setup + "INSERT INTO customer VALUES (3, 'c@x', 0);", "CHECK",
                "n not above 0");
        assertRefused(version, FOREIGN_KEYS + setup + "INSERT INTO orders VALUES (13, 99);", "FOREIGN KEY",
                "an order of no customer");

        SqliteCli.Result cascade = SqliteCli.run(version, FOREIGN_KEYS + setup
                + "DELETE FROM customer WHERE id = 1; SELECT 'orders ' || count(*) FROM orders;");
        assertThat(cascade.failed()).as(cascade.err()).isFalse();
        assertThat(cascade.out()).as("ON DELETE CASCADE takes the customer's orders").containsExactly("orders 2");

        SqliteCli.Result off = SqliteCli.run(version, setup
                + "INSERT INTO orders VALUES (13, 99); SELECT 'orders ' || count(*) FROM orders;");
        assertThat(off.failed()).as(off.err()).isFalse();
        assertThat(off.out()).as("without PRAGMA foreign_keys the key is not kept").containsExactly("orders 4");

        assertRefused(version, "CREATE TABLE customer(id INTEGER PRIMARY KEY);"
                + " CREATE TABLE r(cid INTEGER, FOREIGN KEY (cid) REFERENCES \"main\".\"customer\" (id));",
                "syntax error", "REFERENCES a table named with its schema");

        // ALTER TABLE … ADD: none in any version
        String plain = "CREATE TABLE customer(id INTEGER PRIMARY KEY, email TEXT, n INTEGER);"
                + " CREATE TABLE orders(id INTEGER, cid INTEGER);\n";
        assertRefused(version, plain + ddl.addUniqueConstraint(CUSTOMER, "uc_email", List.of("email")) + ";",
                "syntax error", "ADD UNIQUE");
        assertRefused(version, plain + ddl.addCheckConstraint(CUSTOMER, "ck_n", "\"n\" > 0") + ";", "syntax error",
                "ADD CHECK");
        assertRefused(version, plain + ddl.addForeignKeyConstraint(ORDERS, "fk_orders_customer", List.of("cid"),
                CUSTOMER, List.of("id"), null, null) + ";", "syntax error", "ADD FOREIGN KEY");
    }

    private static void assertRefused(String version, String sql, String error, String what) {
        SqliteCli.Result r = SqliteCli.run(version, sql);
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
