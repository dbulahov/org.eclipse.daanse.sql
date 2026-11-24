/*
 * Copyright (c) 2026 Contributors to the Eclipse Foundation.
 *
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 */
package org.eclipse.daanse.sql.dialect.api.generator;

import java.util.List;
import java.util.Optional;

/**
 * The clause that brings back what an {@code INSERT}, an {@code UPDATE} or a {@code DELETE} wrote, in one of two forms:
 * <ul>
 * <li>{@link #returning(List)}: the statement becomes a query, its rows read with {@code executeQuery}
 * ({@code RETURNING} of PostgreSQL, MariaDB and SQLite, {@code OUTPUT} of SQL Server);</li>
 * <li>{@link #returningInto(List)}: the values go into out parameters, one per column, bound after the statement's own
 * parameters ({@code RETURNING … INTO} of Oracle).</li>
 * </ul>
 * A dialect has either, both or none; each is asked for on its own.
 */
public interface ReturningGenerator {

    /**
     * The clause that makes the statement return the given columns as rows, read with {@code executeQuery}.
     *
     * @return empty where the dialect has no such clause ({@link #supportsReturning()} is false), and for no columns
     */
    default Optional<String> returning(List<String> columns) {
        return Optional.empty();
    }

    /** Whether {@link #returning(List)} writes a clause: the statement returns its rows. */
    default boolean supportsReturning() {
        return false;
    }

    /**
     * {@code RETURNING c1, c2 INTO ?, ?}: the clause that puts the given columns of the rows the statement wrote into
     * out parameters, one {@code ?} per column, in the order of the columns, after the statement's own parameters. The
     * statement is executed with {@code executeUpdate}, and the values are read as the driver serves out parameters of
     * a DML statement (Oracle: {@code OraclePreparedStatement.registerReturnParameter} and
     * {@code getReturnResultSet}, a row per row written).
     *
     * @return empty where the dialect has no such clause ({@link #supportsReturningInto()} is false), for no columns,
     *         and for {@code *}, which names no column a parameter can be bound to
     */
    default Optional<String> returningInto(List<String> columns) {
        return Optional.empty();
    }

    /** Whether {@link #returningInto(List)} writes a clause: the statement returns into out parameters. */
    default boolean supportsReturningInto() {
        return false;
    }
}
